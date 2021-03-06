package dt.sql.alarm.core

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import dt.sql.alarm.input.SourceInfo
import Constants._
import dt.sql.alarm.filter.SQLFilter
import dt.sql.alarm.output.SinkInfo
import org.apache.spark.rdd.RDD
import tech.sqlclub.common.log.Logging
import tech.sqlclub.common.utils.ConfigUtils
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import tech.sqlclub.common.exception.SQLClubException

object SparkRuntime extends Logging {
  private var sparkSession :SparkSession = null
  var sparkConfMap:Map[String,String] = null
  var streamingQuery:StreamingQuery = null

  def getSparkSession:SparkSession = {
    if (sparkSession == null) {
      this.synchronized {
        if (sparkSession == null) {
          WowLog.logInfo("create Spark Runtime....")
          val params = ConfigUtils.toStringMap
          val conf = new SparkConf()
          params.filter(f =>
            f._1.startsWith("spark.") ||
              f._1.startsWith("hive.")
          ).foreach { f =>
            conf.set(f._1, f._2)
          }
          conf.setAppName(ConfigUtils.getStringValue(appName))
          if (ConfigUtils.hasConfig(master)) {
            conf.setMaster(ConfigUtils.getStringValue(master))
          }
          sparkSession = SparkSession.builder().config(conf).getOrCreate()
          sparkConfMap = sparkSession.conf.getAll
          WowLog.logInfo("Spark Runtime created!!!")
        }
      }
    }
    sparkSession
  }

  def parseProcessAndSink(spark:SparkSession) = {
    WowLog.logInfo("spark parse process and sink start...")
    val sources = getSourceTable(spark)
    WowLog.logInfo("spark stream get all source table succeed!")
    logInfo("All source data schema: ")
    sources.printSchema()
    val dStreamWriter = sources.writeStream.foreachBatch{
      (batchTable, batchId) =>
        WowLog.logInfo(s"start processing batch: $batchId")
        val start = System.nanoTime()
        AlarmFlow.run(batchTable){
          // filterFunc
          (table, rule) =>
            val filterTable = SQLFilter.process(rule, table)
            import spark.implicits._
            filterTable.as[AlarmRecord]
        }{
          // sinkFunc
          table =>
            sinks.map(_.process(table))
        }{
          // alertFunc
          (table, rule)=>
           AlarmAlert.push(rule.item_id, rule.source, table)
        }
        val end = System.nanoTime()
        WowLog.logInfo(s"bath $batchId processing is done. Total time consuming: ${(end-start)/1000000} ms.")
    }

    streamingQuery = dStreamWriter
      .queryName(ConfigUtils.getStringValue(appName))
      .option("checkpointLocation", ConfigUtils.getStringValue(checkpoint))
      .trigger(Trigger.ProcessingTime(sparkConfMap.getOrElse(trigger,
        ConfigUtils.getStringValue(trigger, "3000")).toLong)) // 默认3s
      .start()
  }

  private lazy val sinks = getSinks

  def getSinks = {
    val sinks = ConfigUtils.getStringValue(SQLALARM_SINKS)
    val sinkNames = sinks.split(",").filterNot(_.isEmpty)

    assert(sinkNames.filterNot(SinkInfo.sinkExist(_)).size == 0,
      s"Check the configuration of sink, at present only supported: ${SinkInfo.getAllSink}"
    )
    sinkNames.map(SinkInfo.getSink(_))
  }

  def getSourceTable(spark:SparkSession) = {
    val sources_ = ConfigUtils.getStringValue(SQLALARM_SOURCES)

    val sourceNames = sources_.split(",").filterNot(_.isEmpty)

    assert(sourceNames.filterNot(SourceInfo.sourceExist(_)).size == 0,
    s"Check the configuration of sources, at present only supported: ${SourceInfo.getAllSource}"
    )

    val sources = sourceNames.map {
      sourceName =>
        logInfo(s"spark stream create source $sourceName!")
        SourceInfo.getSource(sourceName).getDataSetStream(spark)
    }

    sources.filter(_ != null).reduce(_ union _)
  }
}

object RedisOperations {
  import redis.clients.jedis.Jedis
  import com.redislabs.provider.redis._
  import com.redislabs.provider.redis.util.ConnectionUtils
  lazy private val sc = SparkRuntime.getSparkSession.sparkContext

  lazy private val redisEndpoint = RedisConfig.fromSparkConf(sc.getConf).initialHost

  def IncorrectKeysOrKeyPatternMsg = "RedisOperations KeysOrKeyPattern should be String or Array[String]"

  def getTableCache[T](keysOrKeyPattern: T):RDD[(String, String)] = {
    keysOrKeyPattern match {
      case keyPattern: String => sc.fromRedisHash(keyPattern.asInstanceOf[String])
      case keys: Array[String] => sc.fromRedisHash(keys.asInstanceOf[Array[String]])
      case _ => throw new SQLClubException(IncorrectKeysOrKeyPatternMsg)
    }
  }

  def getTableCache(key: String, field:String)
    (implicit conn:Jedis = redisEndpoint.connect()):String = {
    ConnectionUtils.withConnection[String](conn) {
      conn =>
        conn.hget(key, field)
    }
  }


  def addTableCache(key: String, field: String, value: String)
    (implicit conn:Jedis = redisEndpoint.connect()): Long = {
    ConnectionUtils.withConnection[Long](conn) {
      conn =>
        conn.hset(key, field, value)
    }
  }


  def getListCache[T](keysOrKeyPattern:T):RDD[String] = {
    keysOrKeyPattern match {
      case keyPattern: String => sc.fromRedisList(keyPattern.asInstanceOf[String])
      case keys: Array[String] => sc.fromRedisList(keys.asInstanceOf[Array[String]])
      case _ => throw new SQLClubException(IncorrectKeysOrKeyPatternMsg)
    }

  }
}
