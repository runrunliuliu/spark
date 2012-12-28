package spark.rdd

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

import spark.{Aggregator, Logging, Partitioner, RDD, SparkEnv, Split, TaskContext}
import spark.{Dependency, OneToOneDependency, ShuffleDependency}


private[spark] sealed trait CoGroupSplitDep extends Serializable
private[spark] case class NarrowCoGroupSplitDep(rdd: RDD[_], split: Split) extends CoGroupSplitDep
private[spark] case class ShuffleCoGroupSplitDep(shuffleId: Int) extends CoGroupSplitDep

private[spark]
class CoGroupSplit(idx: Int, val deps: Seq[CoGroupSplitDep]) extends Split with Serializable {
  override val index: Int = idx
  override def hashCode(): Int = idx
}

private[spark] class CoGroupAggregator
  extends Aggregator[Any, Any, ArrayBuffer[Any]](
    { x => ArrayBuffer(x) },
    { (b, x) => b += x },
    { (b1, b2) => b1 ++ b2 })
  with Serializable

class CoGroupedRDD[K](@transient rdds: Seq[RDD[(_, _)]], part: Partitioner)
  extends RDD[(K, Seq[Seq[_]])](rdds.head.context) with Logging {

  val aggr = new CoGroupAggregator

  @transient
  override val dependencies = {
    val deps = new ArrayBuffer[Dependency[_]]
    for ((rdd, index) <- rdds.zipWithIndex) {
      val mapSideCombinedRDD = rdd.mapPartitions(aggr.combineValuesByKey(_), true)
      if (mapSideCombinedRDD.partitioner == Some(part)) {
        logInfo("Adding one-to-one dependency with " + mapSideCombinedRDD)
        deps += new OneToOneDependency(mapSideCombinedRDD)
      } else {
        logInfo("Adding shuffle dependency with " + rdd)
        deps += new ShuffleDependency[Any, ArrayBuffer[Any]](mapSideCombinedRDD, part)
      }
    }
    deps.toList
  }

  @transient
  val splits_ : Array[Split] = {
    val firstRdd = rdds.head
    val array = new Array[Split](part.numPartitions)
    for (i <- 0 until array.size) {
      array(i) = new CoGroupSplit(i, rdds.zipWithIndex.map { case (r, j) =>
        dependencies(j) match {
          case s: ShuffleDependency[_, _] =>
            new ShuffleCoGroupSplitDep(s.shuffleId): CoGroupSplitDep
          case _ =>
            new NarrowCoGroupSplitDep(r, r.splits(i)): CoGroupSplitDep
        }
      }.toList)
    }
    array
  }

  override def splits = splits_

  override val partitioner = Some(part)

  override def preferredLocations(s: Split) = Nil

  override def compute(s: Split, context: TaskContext): Iterator[(K, Seq[Seq[_]])] = {
    val split = s.asInstanceOf[CoGroupSplit]
    val numRdds = split.deps.size
    val map = new HashMap[K, Seq[ArrayBuffer[Any]]]
    def getSeq(k: K): Seq[ArrayBuffer[Any]] = {
      map.getOrElseUpdate(k, Array.fill(numRdds)(new ArrayBuffer[Any]))
    }
    for ((dep, depNum) <- split.deps.zipWithIndex) dep match {
      case NarrowCoGroupSplitDep(rdd, itsSplit) => {
        // Read them from the parent
        for ((k, v) <- rdd.iterator(itsSplit, context)) {
          getSeq(k.asInstanceOf[K])(depNum) += v
        }
      }
      case ShuffleCoGroupSplitDep(shuffleId) => {
        // Read map outputs of shuffle
        def mergePair(pair: (K, Seq[Any])) {
          val mySeq = getSeq(pair._1)
          for (v <- pair._2)
            mySeq(depNum) += v
        }
        val fetcher = SparkEnv.get.shuffleFetcher
        fetcher.fetch[K, Seq[Any]](shuffleId, split.index).foreach(mergePair)
      }
    }
    map.iterator
  }
}
