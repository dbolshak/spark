package spark

import java.io.EOFException
import java.net.URL
import java.io.ObjectInputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.HashSet
import java.util.Random
import java.util.Date

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Map
import scala.collection.mutable.HashMap

import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.HadoopFileWriter
import org.apache.hadoop.mapred.OutputFormat
import org.apache.hadoop.mapred.TextOutputFormat
import org.apache.hadoop.mapred.SequenceFileOutputFormat
import org.apache.hadoop.mapred.OutputCommitter
import org.apache.hadoop.mapred.FileOutputCommitter
import org.apache.hadoop.io.Writable
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.io.BytesWritable
import org.apache.hadoop.io.Text

import SparkContext._

import mesos._

@serializable
abstract class RDD[T: ClassManifest](@transient sc: SparkContext) {
  // Methods that must be implemented by subclasses
  def splits: Array[Split]
  def compute(split: Split): Iterator[T]
  val dependencies: List[Dependency[_]]
  
  // Optionally overridden by subclasses to specify how they are partitioned
  val partitioner: Option[Partitioner] = None

  // Optionally overridden by subclasses to specify placement preferences
  def preferredLocations(split: Split): Seq[String] = Nil
  
  def context = sc
  
  // Get a unique ID for this RDD
  val id = sc.newRddId()
  
  // Variables relating to caching
  private var shouldCache = false
  
  // Change this RDD's caching
  def cache(): RDD[T] = {
    shouldCache = true
    this
  }
  
  // Read this RDD; will read from cache if applicable, or otherwise compute
  final def iterator(split: Split): Iterator[T] = {
    if (shouldCache) {
      SparkEnv.get.cacheTracker.getOrCompute[T](this, split)
    } else {
      compute(split)
    }
  }
  
  // Transformations
  
  def map[U: ClassManifest](f: T => U): RDD[U] = new MappedRDD(this, sc.clean(f))
  
  def flatMap[U: ClassManifest](f: T => Traversable[U]): RDD[U] =
    new FlatMappedRDD(this, sc.clean(f))
  
  def filter(f: T => Boolean): RDD[T] = new FilteredRDD(this, sc.clean(f))

  def sample(withReplacement: Boolean, frac: Double, seed: Int): RDD[T] =
    new SampledRDD(this, withReplacement, frac, seed)

  def union(other: RDD[T]): RDD[T] = new UnionRDD(sc, Array(this, other))

  def ++(other: RDD[T]): RDD[T] = this.union(other)

  def glom(): RDD[Array[T]] = new SplitRDD(this)

  def cartesian[U: ClassManifest](other: RDD[U]): RDD[(T, U)] =
    new CartesianRDD(sc, this, other)

  def groupBy[K: ClassManifest](func: T => K, numSplits: Int): RDD[(K, Seq[T])] =
    this.map(t => (func(t), t)).groupByKey(numSplits)

  def groupBy[K: ClassManifest](func: T => K): RDD[(K, Seq[T])] =
    groupBy[K](func, sc.numCores)

  // Parallel operations
  
  def foreach(f: T => Unit) {
    val cleanF = sc.clean(f)
    sc.runJob(this, (iter: Iterator[T]) => iter.foreach(cleanF))
  }

  def collect(): Array[T] = {
    val results = sc.runJob(this, (iter: Iterator[T]) => iter.toArray)
    Array.concat(results: _*)
  }

  def reduce(f: (T, T) => T): T = {
    val cleanF = sc.clean(f)
    val reducePartition: Iterator[T] => Option[T] = iter => {
      if (iter.hasNext)
        Some(iter.reduceLeft(f))
      else
        None
    }
    val options = sc.runJob(this, reducePartition)
    val results = new ArrayBuffer[T]
    for (opt <- options; elem <- opt)
      results += elem
    if (results.size == 0)
      throw new UnsupportedOperationException("empty collection")
    else
      return results.reduceLeft(f)
  }
  
  def count(): Long = {
    sc.runJob(this, (iter: Iterator[T]) => {
      var result = 0L
      while (iter.hasNext) {
        result += 1L
        iter.next
      }
      result
    }).sum
  }

  def toArray(): Array[T] = collect()
  
  override def toString(): String = {
    "%s(%d)".format(getClass.getSimpleName, id)
  }

  // Take the first num elements of the RDD. This currently scans the partitions
  // *one by one*, so it will be slow if a lot of partitions are required. In that
  // case, use collect() to get the whole RDD instead.
  def take(num: Int): Array[T] = {
    if (num == 0)
      return new Array[T](0)
    val buf = new ArrayBuffer[T]
    var p = 0
    while (buf.size < num && p < splits.size) {
      val left = num - buf.size
      val res = sc.runJob(this, (it: Iterator[T]) => it.take(left).toArray, Array(p))
      buf ++= res(0)
      if (buf.size == num)
        return buf.toArray
      p += 1
    }
    return buf.toArray
  }

  def first: T = take(1) match {
    case Array(t) => t
    case _ => throw new UnsupportedOperationException("empty collection")
  }

  def saveAsTextFile(path: String) {
    this.map( x => (NullWritable.get(), new Text(x.toString))).saveAsHadoopFile[TextOutputFormat[NullWritable, Text], FileOutputCommitter](path)
  }

  def saveAsObjectFile(path: String) {
    this.glom.map( x => (NullWritable.get(), new BytesWritable(Utils.serialize(x))) ).saveAsSequenceFile(path)
  }
}

class MappedRDD[U: ClassManifest, T: ClassManifest](
  prev: RDD[T], f: T => U)
extends RDD[U](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override def compute(split: Split) = prev.iterator(split).map(f)
}

class FlatMappedRDD[U: ClassManifest, T: ClassManifest](
  prev: RDD[T], f: T => Traversable[U])
extends RDD[U](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override def compute(split: Split) = prev.iterator(split).toStream.flatMap(f).iterator
}

class FilteredRDD[T: ClassManifest](
  prev: RDD[T], f: T => Boolean)
extends RDD[T](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override def compute(split: Split) = prev.iterator(split).filter(f)
}

class SplitRDD[T: ClassManifest](prev: RDD[T])
extends RDD[Array[T]](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override def compute(split: Split) = Array(prev.iterator(split).toArray).iterator
}


@serializable class PairRDDExtras[K: ClassManifest, V: ClassManifest](self: RDD[(K, V)]) extends Logging {
  def reduceByKeyToDriver(func: (V, V) => V): Map[K, V] = {
    def mergeMaps(m1: HashMap[K, V], m2: HashMap[K, V]): HashMap[K, V] = {
      for ((k, v) <- m2) {
        m1.get(k) match {
          case None => m1(k) = v
          case Some(w) => m1(k) = func(w, v)
        }
      }
      return m1
    }
    self.map(pair => HashMap(pair)).reduce(mergeMaps)
  }

  def combineByKey[C](createCombiner: V => C,
                      mergeValue: (C, V) => C,
                      mergeCombiners: (C, C) => C,
                      numSplits: Int)
  : RDD[(K, C)] =
  {
    val aggregator = new Aggregator[K, V, C](createCombiner, mergeValue, mergeCombiners)
    val partitioner = new HashPartitioner(numSplits)
    new ShuffledRDD(self, aggregator, partitioner)
  }

  def reduceByKey(func: (V, V) => V, numSplits: Int): RDD[(K, V)] = {
    combineByKey[V]((v: V) => v, func, func, numSplits)
  }

  def groupByKey(numSplits: Int): RDD[(K, Seq[V])] = {
    def createCombiner(v: V) = ArrayBuffer(v)
    def mergeValue(buf: ArrayBuffer[V], v: V) = buf += v
    def mergeCombiners(b1: ArrayBuffer[V], b2: ArrayBuffer[V]) = b1 ++= b2
    val bufs = combineByKey[ArrayBuffer[V]](
      createCombiner _, mergeValue _, mergeCombiners _, numSplits)
    bufs.asInstanceOf[RDD[(K, Seq[V])]]
  }

  def join[W](other: RDD[(K, W)], numSplits: Int): RDD[(K, (V, W))] = {
    val vs: RDD[(K, Either[V, W])] = self.map { case (k, v) => (k, Left(v)) }
    val ws: RDD[(K, Either[V, W])] = other.map { case (k, w) => (k, Right(w)) }
    (vs ++ ws).groupByKey(numSplits).flatMap {
      case (k, seq) => {
        val vbuf = new ArrayBuffer[V]
        val wbuf = new ArrayBuffer[W]
        seq.foreach(_ match {
          case Left(v) => vbuf += v
          case Right(w) => wbuf += w
        })
        for (v <- vbuf; w <- wbuf) yield (k, (v, w))
      }
    }
  }

  def combineByKey[C](createCombiner: V => C,
                      mergeValue: (C, V) => C,
                      mergeCombiners: (C, C) => C)
  : RDD[(K, C)] = {
    combineByKey(createCombiner, mergeValue, mergeCombiners, numCores)
  }

  def reduceByKey(func: (V, V) => V): RDD[(K, V)] = {
    reduceByKey(func, numCores)
  }

  def groupByKey(): RDD[(K, Seq[V])] = {
    groupByKey(numCores)
  }

  def join[W](other: RDD[(K, W)]): RDD[(K, (V, W))] = {
    join(other, numCores)
  }

  def numCores = self.context.numCores

  def collectAsMap(): Map[K, V] = HashMap(self.collect(): _*)
  
  def mapValues[U](f: V => U): RDD[(K, U)] = {
    val cleanF = self.context.clean(f)
    new MappedValuesRDD(self, cleanF)
  }
  
  def flatMapValues[U](f: V => Traversable[U]): RDD[(K, U)] = {
    val cleanF = self.context.clean(f)
    new FlatMappedValuesRDD(self, cleanF)
  }
  
  def groupWith[W](other: RDD[(K, W)]): RDD[(K, (Seq[V], Seq[W]))] = {
    val part = self.partitioner match {
      case Some(p) => p
      case None => new HashPartitioner(numCores)
    }
    new CoGroupedRDD[K](Seq(self.asInstanceOf[RDD[(_, _)]], other.asInstanceOf[RDD[(_, _)]]), part).map {
      case (k, Seq(vs, ws)) =>
        (k, (vs.asInstanceOf[Seq[V]], ws.asInstanceOf[Seq[W]]))
    }
  }
  
  def groupWith[W1, W2](other1: RDD[(K, W1)], other2: RDD[(K, W2)])
      : RDD[(K, (Seq[V], Seq[W1], Seq[W2]))] = {
    val part = self.partitioner match {
      case Some(p) => p
      case None => new HashPartitioner(numCores)
    }
    new CoGroupedRDD[K](
        Seq(self.asInstanceOf[RDD[(_, _)]], 
            other1.asInstanceOf[RDD[(_, _)]], 
            other2.asInstanceOf[RDD[(_, _)]]),
        part).map {
      case (k, Seq(vs, w1s, w2s)) =>
        (k, (vs.asInstanceOf[Seq[V]], w1s.asInstanceOf[Seq[W1]], w2s.asInstanceOf[Seq[W2]]))
    }
  }
  
  def saveAsHadoopFile (path: String, jobConf: JobConf) {
    saveAsHadoopFile(path, jobConf.getOutputKeyClass, jobConf.getOutputValueClass, jobConf.getOutputFormat().getClass.asInstanceOf[Class[OutputFormat[AnyRef,AnyRef]]], jobConf.getOutputCommitter().getClass.asInstanceOf[Class[OutputCommitter]], jobConf)
  }

  def saveAsHadoopFile [F <: OutputFormat[K,V], C <: OutputCommitter] (path: String) (implicit fm: ClassManifest[F], cm: ClassManifest[C]) {
    saveAsHadoopFile(path, fm.erasure.asInstanceOf[Class[F]], cm.erasure.asInstanceOf[Class[C]])
  }
  
  def saveAsHadoopFile(path: String, outputFormatClass: Class[_ <: OutputFormat[K,V]], outputCommitterClass: Class[_ <: OutputCommitter]) {
    saveAsHadoopFile(path,  implicitly[ClassManifest[K]].erasure, implicitly[ClassManifest[V]].erasure, outputFormatClass, outputCommitterClass)
  }

  def saveAsHadoopFile(path: String, keyClass: Class[_], valueClass: Class[_], outputFormatClass: Class[_ <: OutputFormat[_,_]], outputCommitterClass: Class[_ <: OutputCommitter]) {
    saveAsHadoopFile(path, keyClass, valueClass, outputFormatClass, outputCommitterClass, null)
  }
  
  private def saveAsHadoopFile(path: String, keyClass: Class[_], valueClass: Class[_], outputFormatClass: Class[_ <: OutputFormat[_,_]], outputCommitterClass: Class[_ <: OutputCommitter], jobConf: JobConf) {
    logInfo ("Saving as hadoop file of type (" + keyClass.getSimpleName+ "," +valueClass.getSimpleName+ ")" ) 
    val writer = new HadoopFileWriter(path, 
                                      keyClass, 
                                      valueClass, 
                                      outputFormatClass.asInstanceOf[Class[OutputFormat[AnyRef,AnyRef]]],
                                      outputCommitterClass.asInstanceOf[Class[OutputCommitter]],
                                      null)
    writer.preSetup()

    def writeToFile (context: TaskContext, iter: Iterator[(K,V)]): HadoopFileWriter = {
      writer.setup(context.stageId, context.splitId, context.attemptId)
      writer.open()
      
      var count = 0
      while(iter.hasNext) {
        val record = iter.next
        count += 1
        writer.write(record._1.asInstanceOf[AnyRef], record._2.asInstanceOf[AnyRef])
      }
    
      writer.close()
      return writer
    }

    self.context.runJob(self, writeToFile _ ).foreach(_.commit())
    writer.cleanup()
  }

  def getKeyClass() = implicitly[ClassManifest[K]].erasure

  def getValueClass() = implicitly[ClassManifest[V]].erasure
}

@serializable
class SequencePairRDDExtras[K <% Writable: ClassManifest, V <% Writable : ClassManifest](self: RDD[(K,V)]) extends Logging { 
  
  def getWritableClass[T <% Writable: ClassManifest](): Class[_ <: Writable] = {
    val c = {
      if (classOf[Writable].isAssignableFrom(classManifest[T].erasure)) 
        classManifest[T].erasure
      else
        implicitly[T => Writable].getClass.getMethods()(0).getReturnType
    }
    c.asInstanceOf[Class[ _ <: Writable]]
  }

  def saveAsSequenceFile(path: String) {
    
    def anyToWritable[U <% Writable](u: U): Writable = u

    val keyClass = getWritableClass[K]
    val valueClass = getWritableClass[V]
    val convertKey = !classOf[Writable].isAssignableFrom(self.getKeyClass)
    val convertValue = !classOf[Writable].isAssignableFrom(self.getValueClass)
  
    logInfo("Saving as sequence file of type (" + keyClass.getSimpleName + "," + valueClass.getSimpleName + ")" ) 
    if (!convertKey && !convertValue) {
      self.saveAsHadoopFile(path, keyClass, valueClass, classOf[SequenceFileOutputFormat[Writable,Writable]], classOf[FileOutputCommitter]) 
    } else if (!convertKey && convertValue) {
      self.map(x => (x._1,anyToWritable(x._2))).saveAsHadoopFile(path, keyClass, valueClass, classOf[SequenceFileOutputFormat[Writable,Writable]], classOf[FileOutputCommitter]) 
    } else if (convertKey && !convertValue) {
      self.map(x => (anyToWritable(x._1),x._2)).saveAsHadoopFile(path, keyClass, valueClass, classOf[SequenceFileOutputFormat[Writable,Writable]], classOf[FileOutputCommitter]) 
    } else if (convertKey && convertValue) {
      self.map(x => (anyToWritable(x._1),anyToWritable(x._2))).saveAsHadoopFile(path, keyClass, valueClass, classOf[SequenceFileOutputFormat[Writable,Writable]], classOf[FileOutputCommitter]) 
    } 
  }
}

class MappedValuesRDD[K, V, U](
  prev: RDD[(K, V)], f: V => U)
extends RDD[(K, U)](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override val partitioner = prev.partitioner
  override def compute(split: Split) =
    prev.iterator(split).map{case (k, v) => (k, f(v))}
}

class FlatMappedValuesRDD[K, V, U](
  prev: RDD[(K, V)], f: V => Traversable[U])
extends RDD[(K, U)](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override val partitioner = prev.partitioner
  override def compute(split: Split) = {
    prev.iterator(split).toStream.flatMap { 
      case (k, v) => f(v).map(x => (k, x))
    }.iterator
  }
}
