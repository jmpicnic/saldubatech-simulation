/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.events

import com.typesafe.scalalogging.Logger
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{ExecutionContext, Future}


object EventStore{
	var store: Option[EventStore] = None

	def apply(dbConfigName: String, tableName: String): EventStore = {store = Some(new EventStore(dbConfigName, tableName)); store.get}

	def close(): Unit = if (store isDefined) store.get.close else throw new IllegalStateException("Event Store not initialized")

	type EventColumnType = (String, Long, String, String, String, String)

}

class EventStore private (dbConfigName: String, tableName: String) {
	import EventStore._

	private val log = Logger(this.getClass.getName)

	class EventTable(tag: Tag) extends Table[EventColumnType](tag, tableName) {
		def uid = column[String]("evid", O.PrimaryKey)
		def ts = column[Long]("ts")
		def evCategory = column[String]("ev_category")
		def evType = column[String]("ev_type")
		def statiomId = column[String]("station_id")
		def loadId = column[String]("load_id")
		def * = (uid, ts, evCategory, evType, statiomId, loadId)
	}

	private val db: Database = Database.forConfig(dbConfigName)

	def close: Unit = {
		db.run(SimpleDBIO[Unit](_.connection.commit()))
		implicit val ec: ExecutionContext = ExecutionContext.global
		db.run(TableQuery[EventTable].filter(_.ts === -1L).result).onComplete(_ => log.info("Completed Spooler work"))
		db.close
	}

	def freshQuery:TableQuery[EventTable] = TableQuery[EventTable]

	val createIfNotExists = freshQuery.schema.createIfNotExists
	def initialize: Future[Unit] = {
		db.run(createIfNotExists)
	}

	val dropIfExists = freshQuery.schema.dropIfExists
	val createTable = freshQuery.schema.create
	def refresh: Future[Unit] = {
		val composite:DBIO[Unit] = DBIO.seq(dropIfExists, createTable)
		log.info(s"Refreshing DB: ${composite.toString}")
		db.run(composite)
	}

	def spool(evs: Seq[Event]): Future[Option[Int]] = {
		//DBIO[Option[Int]]

		val tuples = evs.map {ev => ev.asTuple}
		log.debug(s"Tuples to spool $tuples")
		val spoolQuery = TableQuery[EventTable] ++= tuples
		log.debug(s"Spooling: ${spoolQuery.statements.toString}")
		db.run(spoolQuery)//.onComplete(log.info("Completed Query"))

	}

	def runQuery(q: TableQuery[EventTable]): Future[Seq[EventColumnType]] = {
		val action = q.result
		db.run(action)
	}

}



