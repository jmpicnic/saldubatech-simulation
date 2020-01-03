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

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.generic

import akka.actor.{ActorRef, Props}
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.v1.OneWayChannel
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl}
import com.saldubatech.v1.equipment.elements.Induct
import com.saldubatech.v1.events.OperationalEvent


object Sink{
	def props(name: String, gw: Gateway): Props = Props(new Sink(name, gw))
}

class Sink(val name: String, gw: Gateway)
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
