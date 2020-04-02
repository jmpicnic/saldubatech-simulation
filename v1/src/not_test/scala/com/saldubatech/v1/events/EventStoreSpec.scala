/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events

import com.saldubatech.test.utils.BaseSpec
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EventStoreSpec extends BaseSpec {
	val dbConfigName = "testPgDb"
	val tableName = "m_m_1"
	val store: EventStore = EventStore(dbConfigName, tableName)

	"An Event Store" when {
		"created on a pre-exising DB and Table" should {
			"Allow for insertion of events" in {
				//store.refresh
				val result = store.spool(Seq(Event(1, DummyEventType.DUMMY_VAL, "MockStation", "first"),
					Event(2, DummyEventType.DUMMY_VAL, "MockStation", "second")))
				Await.result(result, Duration.Inf)
			}
			"that can then be retrieved using a fresh query" in {
				val query = store.freshQuery.filter(_.ts < 200L).map(r => (r.ts, r.evType))
				query.result.statements.map(_.toString).head shouldBe
					s"""select "ts", "ev_type" from "$tableName" where "ts" < 200"""
			}
		}
	}
	it when {
		"created on any DB" should {
			"be able to drop the required table if it exists" in {
				store.dropIfExists.statements.head shouldBe s"""drop table if exists "$tableName""""
			}
			"be able to create the table" in {
				store.createTable.statements.head.shouldBe(
					s"""create table "$tableName" ("evid" VARCHAR NOT NULL PRIMARY KEY,"""+
						""""ts" BIGINT NOT NULL,"ev_category" VARCHAR NOT NULL,"ev_type" VARCHAR NOT NULL,"""+
						""""station_id" VARCHAR NOT NULL,"load_id" VARCHAR NOT NULL)""")
			}
			"be able to check for table existance when creating" in {
				store.createIfNotExists.statements.head.shouldBe(s"""create table if not exists "$tableName" """
					+"""("evid" VARCHAR NOT NULL PRIMARY KEY,"ts" BIGINT NOT NULL,"ev_category" VARCHAR NOT NULL,"""+
					""""ev_type" VARCHAR NOT NULL,"station_id" VARCHAR NOT NULL,"load_id" VARCHAR NOT NULL)""")
			}
		}
	}

	override def afterAll() {
		store.close
	}

}
