/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events

object DBEventSpooler{
	def apply(store: EventStore): EventSpooler = new DBEventSpooler(store)
}

class DBEventSpooler(store: EventStore) extends EventSpooler {

	override protected def doFlush(events: Seq[Event]): Unit = {
		store.spool(events.toList)
	}

	override def doClose: Unit = store.close
}
