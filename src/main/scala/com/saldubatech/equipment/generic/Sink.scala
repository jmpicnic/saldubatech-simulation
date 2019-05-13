/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.generic

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.{Material, OneWayChannel}
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.equipment.elements.Induct
import com.saldubatech.events.OperationalEvent


object Sink{
	def props(name: String, gw: Gateway): Props = Props(new Sink(name, gw))
}

class Sink(name: String, gw: Gateway)
  extends SimActorImpl(name,gw)
	  with Induct
	  with Induct.Processor {
	val inductProcessor: Induct.Processor = this


	def newJobArrival(operation: Material, at: Long): Unit = {
		log.debug(s"Consuming input from Sink New Arrival: $operation at: $at")
		consumeInput(operation, at)
		// streamer.capture(operation, at)
		log.debug(s"Operation arrived: $operation")
		collect(at, OperationalEvent.Depart, name, operation.uid)
		collect(at, OperationalEvent.End,name,operation.uid)
	}

	override def configure: SimActorImpl.Configuring = inductConfiguring

	override def process(from: ActorRef, at: Long): Processing = inducting(from, at)

	override def onRestore(via: OneWayChannel.Endpoint[Material], at: Long): Unit = {}
}
