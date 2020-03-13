/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.ActorRef
import com.saldubatech.ddes.SimActor
import com.saldubatech.events.EventCollector.Report
import com.saldubatech.events.{Event, EventTypeEnum}

trait EquipmentActorMixIn
	extends SimActor{
	private val eventCollector: Option[ActorRef] = gw.eventCollector
	val name: String

	def collect(ev: Event): Unit = {
		eventCollector.foreach(_ ! Report(name, ev))
//		log.debug(s"collecting ev: $ev")
	}

	def collect(ts: Long, evType: EventTypeEnum#CategorizedVal, stationId: String, loadId: String): Unit = {
		collect(Event(ts, evType, stationId, loadId))
	}
}
