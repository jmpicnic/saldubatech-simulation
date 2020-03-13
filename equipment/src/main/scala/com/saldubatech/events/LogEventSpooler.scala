/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.events

import com.typesafe.scalalogging.Logger

object LogEventSpooler{
	def apply(logger: Logger): LogEventSpooler = new LogEventSpooler(logger)
}

class LogEventSpooler(logger: Logger) extends EventSpooler {
	override protected def doFlush(events: Seq[Event]): Unit = {
		logger.info(s"Flushing ${events.size} events")
		//for (ev <- events) logger.debug(ev.asTuple.toString)
		events.foreach[Unit](ev => logger.debug(ev.asTuple.toString()))
	}

	override def doClose: Unit = {}
}
