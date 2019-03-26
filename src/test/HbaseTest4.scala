import java.util.Properties

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.CellUtil._
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{TableInputFormat, TableOutputFormat}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext
import org.apache.spark.graphx.lib.LabelPropagation
import org.apache.spark.graphx.{EdgeDirection, _}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.graphframes.GraphFrame
import org.graphstream.graph.implementations.{AbstractEdge, SingleGraph, SingleNode}

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
  * Created by Administrator on 2018/12/4.
  */
object HbaseTest4 {
  def main(args: Array[String]) {
    // 屏蔽不必要的日志显示在终端上
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)

    val spark = SparkSession
      .builder()
      .appName("Graphs")
      .master("local")
      .getOrCreate()

    val sc = spark.sparkContext

    val startRow_0 = "0";
    val endRow_endLine = "~~~~~";
    val startTimeStamp_0L = 0L;
    val endTimeStamp_13_9L = 9999999999999L;
    val tableName = "mapping"
    val family = "cf"

    val rdd = loadData(tableName, startRow_0, endRow_endLine, startTimeStamp_0L, endTimeStamp_13_9L, sc)
//    rdd.foreach(println)

    val vertex1 = rdd.map(x => {
      val src = x._2.get("src").get
      (src.hashCode.toLong, src)
    })

    val vertex2 = rdd.map(x => {
      val dist = x._2.get("dist").get
      (dist.hashCode.toLong, dist)
    })

    val vertexUnion = vertex1.union(vertex2)

    val vertex = spark.createDataFrame(vertexUnion).toDF("id", "name")


//        vertex.foreach(println)

    val edges = spark.createDataFrame(rdd.map(x => {
      val src = x._2.get("src").get.hashCode
      val dist = x._2.get("dist").get.hashCode
      Edge(src, dist, "==")
    })).toDF("src", "dst", "relationship")

    //    edges.foreach(println)

    val gf = GraphFrame(vertex, edges)

    println("~~~~~~~~~~~")
//    gf.triplets.collect().foreach(println)
    val res = gf.find("(a)-[]->(b);(b)-[]->(c); (c)-[]->(d)").distinct()
//    res.show()
//    val tt = spark.sql("select * from ee")
    res.printSchema()
    val select = res.select("a.name", "b.name", "c.name", "d.name")
    select.rdd.map(x => {
      val rowkey = x.getString(0)
      val col2 = x.getString(1)
      val col3 = x.getString(2)
      val col4 = x.getString(3)
      val data = Map("user" -> rowkey, "order" -> col2, "ori" -> col3, "visit" -> col4)
      (rowkey, data)
    }).foreach(println)



  }

  def loadData(tableName: String, startRow: String, stopRow: String, startTimeStamp: Long, endTimeStamp: Long, sparkContext: SparkContext): RDD[(String, Map[String, String])] = {
    getAllRawRDD(sparkContext, tableName, startRow, stopRow, startTimeStamp, endTimeStamp, res2StrMapWithK) //.coalesce(32, false)
  }

  def res2StrMap(res: Result): Map[String, String] = {
    val cells = res.rawCells()
    cells.map(cell => {
      (Bytes.toString(cloneQualifier(cell)), Bytes.toString(cloneValue(cell)))
    }).toMap
  }

  def res2StrMapWithK(res: Result): (String, Map[String, String]) = {
    (Bytes.toString(res.getRow), res2StrMap(res))
  }

  def getAllRawRDD[T: ClassTag](
                                 _sparkContext: SparkContext,
                                 tableName: String,
                                 startRow: String,
                                 stopRow: String,
                                 startTimeStamp: Long,
                                 endTimeStamp: Long,
                                 toT: Result => T
                               ): RDD[T] = {
    val hConf = getHBaseConf(tableName, true)
    hConf.set(TableInputFormat.SCAN_ROW_START, startRow)
    hConf.set(TableInputFormat.SCAN_ROW_STOP, stopRow)
    hConf.set(TableInputFormat.SCAN_TIMERANGE_START, startTimeStamp.toString)
    hConf.set(TableInputFormat.SCAN_TIMERANGE_END, endTimeStamp.toString)

    val rawRDD: RDD[(ImmutableBytesWritable, Result)] = _sparkContext.newAPIHadoopRDD(hConf, classOf[TableInputFormat], classOf[ImmutableBytesWritable], classOf[Result])
    rawRDD.map(res => toT(res._2))
  }

  def getHBaseConf(tableName: String, isInput: Boolean): Configuration = {
    //    println("***HBaseConfiguration.create")
    val hConf = HBaseConfiguration.create()

    //chunqiu
    hConf.set("hbase.zookeeper.quorum", getPropertiesValue("hbase.zookeeper.quorum"))
    //    hConf.set("hbase.zookeeper.quorum", "master20851")
    //    hConf.set("hbase.zookeeper.quorum", "master")
    hConf.set("hbase.zookeeper.property.clientPort", getPropertiesValue("hbase.zookeeper.property.clientPort"))

    hConf.set("zookeeper.znode.parent", getPropertiesValue("zookeeper.znode.parent"))
    //      hConf.set("mapreduce.output.fileoutputformat.outputdir", getPropertiesValue("mapreduce.output.fileoutputformat.outputdir"))
    if (isInput)
      hConf.set(TableInputFormat.INPUT_TABLE, tableName)
    else
      hConf.set(TableOutputFormat.OUTPUT_TABLE, tableName)
    hConf
  }

  def getPropertiesValue(key: String): String = {
    val properties = new Properties()
    val in = HbaseTest.getClass.getClassLoader.getResourceAsStream("app.properties")
    //val path = Thread.currentThread().getContextClassLoader.getResource("config_scala.properties").getPath //文件要放到resource文件夹下
    properties.load(in)
    //    println(properties.getProperty("spring.datasource.url"))
    properties.getProperty(key)
  }
}