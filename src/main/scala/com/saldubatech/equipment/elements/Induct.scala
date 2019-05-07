/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.ActorRef
import com.saldubatech.base.{AbstractChannel, Material, OneWayChannel}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.{Processing, nullProcessing}
import com.saldubatech.events.OperationalEvent

import scala.collection.mutable

object Induct {

	trait Processor {
		def newJobArrival(load: Material, at: Long): Unit
	}
}


trait Induct extends EquipmentActorMixIn
	with OneWayChannel.Destination[Material]
	with StepProcessor.Induct {

	protected def inductConfiguring: Configuring  = channelRightConfiguring
	protected def inducting(from: ActorRef, tick: Long): Processing =
		rightEndpoints.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)


	protected val inductProcessor: Induct.Processor

	private val inbound: mutable.Map[String, AbstractChannel.Endpoint[Material, _]] = mutable.Map()



	// Called as part of the protocol by the configured endpoints.
	override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, at: Long): Unit = {
		log.debug(s"$name Accepting M<aterial $load")
		inbound.put(load.uid, via)
		collect(at, OperationalEvent.Arrive, name, load.uid)
		inductProcessor.newJobArrival(load, at)
	}


	// Implemented to be called from Downstream Elements.
	def consumeInput(load: Material, at: Long): Unit = {  // When the processor has "taken" the job, the entry resource should be returned.
		assert(inbound contains load.uid, s"Load ${load.uid} not found in induct $name")
		log.debug(s"Consuming Input $load in Induct: $name")
		inbound(load.uid).doneWithLoad(load, at)
  }
}
