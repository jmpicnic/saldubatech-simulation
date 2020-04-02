/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events

import com.typesafe.scalalogging.Logger

import scala.collection.mutable


trait EventSpooler {
	protected def doFlush(events: Seq[Event]): Unit
	final def close: Unit = {
		log.info(s"Spooled a total of $totalRecords records")
		doClose
	}

	def doClose: Unit

	protected val log = Logger(this.getClass.getName)

	private var totalRecords: Long = 0
	def flush(): Unit = {
		log.debug(s"Flushing ${pending.size} events")
		doFlush(pending)
		pending.clear()
	}

	def record(ev: Event): Unit = {
		pending += ev
		totalRecords += 1
	}

	private val pending = mutable.ListBuffer[Event]()

}
