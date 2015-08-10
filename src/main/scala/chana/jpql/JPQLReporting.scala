package chana.jpql

import chana.Entity
import chana.jpql.nodes.Statement
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData.Record
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

object JPQLReporting {
  final case class ReportingTick(key: String)

  private def reportingDelay(interval: Duration) = ThreadLocalRandom.current.nextLong(100, interval.toMillis).millis
}

trait JPQLReporting extends Entity {
  import JPQLReporting._

  private var reportingJpqls = List[String]()

  import context.dispatcher
  // TODO: 
  // 1. report once when new-created/new-jpql 
  // 2. report once when deleted  // in case of deleted, should guarantee via ACK etc?
  // 3. report only on updated 
  context.system.scheduler.schedule(1.seconds, 1.seconds, self, ReportingTick(""))

  def JPQLReportingBehavior: Receive = {
    case ReportingTick("") => reportAll(false)
    case ReportingTick(jpqlKey) =>
      DistributedJPQLBoard.keyToStatement.get(jpqlKey) match {
        case null             =>
        case (jpql, interval) => report(jpqlKey, jpql, interval, record)
      }
  }

  def eval(stmt: Statement, record: Record) = {
    val e = new JPQLMapperEvaluator()
    e.collectDataSet(id, stmt, record)
  }

  def reportAll(force: Boolean) {
    var newReportingJpqls = List[String]()
    val jpqls = DistributedJPQLBoard.keyToStatement.entrySet.iterator
    while (jpqls.hasNext) {
      val entry = jpqls.next
      val key = entry.getKey
      if (force || !reportingJpqls.contains(key)) {
        val value = entry.getValue
        report(key, value._1, value._2, record) // report at once
      }
      newReportingJpqls ::= key
    }
    reportingJpqls = newReportingJpqls
  }

  def report(jpqlKey: String, jpql: Statement, interval: FiniteDuration, record: Record) {
    try {
      val dataset = if (isDeleted) null else eval(jpql, record)
      JPQLReducer.reducerProxy(context.system, jpqlKey) ! dataset
      context.system.scheduler.scheduleOnce(interval, self, ReportingTick(jpqlKey))
    } catch {
      case ex: Throwable => log.error(ex, ex.getMessage)
    }
  }

  override def onUpdated(fieldsBefore: Array[(Schema.Field, Any)], recordAfter: Record) {
    reportAll(true)
  }

}
