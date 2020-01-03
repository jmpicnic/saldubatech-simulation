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

package com.saldubatech.v1.equipment.elements

import akka.actor.ActorRef
import com.saldubatech.v1.base.channels.v1.{AbstractChannel, OneWayChannel}
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.events.OperationalEvent

object Discharge {
	trait SelectionPolicy {
		def dischargeSelection(load: Material, outQueues: Map[String,OneWayChannel.Endpoint[Material]]): String
	}


	trait Processor {
		def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit
	}
}

trait Discharge
	extends OneWayChannel.Destination[Material]
		with EquipmentActorMixIn {
	import Discharge._

	val p_outboundSelector: SelectionPolicy

	def discharge: Discharge = this

	def dischargeProcessor: Processor


	// Injected policies
	val outboundSelector: SelectionPolicy = p_outboundSelector

	// Called after a channel receives a Restore
	override def onRestore(via: OneWayChannel.Endpoint[Material], tick: Long): Unit = {
		dischargeProcessor.outboundAvailable(via, tick)
	}

	protected def dischargeConfiguring: Configuring = channelLeftConfiguring

	def deliver(material: Material, at: Long): Boolean = {
		val channel = leftEndpoint(outboundSelector.dischargeSelection(material, allLeftEndpoints))
		log.debug(s"Sending Load: ${material.uid}")
		if (channel.sendLoad(material, at)) {
			log.debug(s"Sent Load: ${material.uid}")
			collect(at, OperationalEvent.Depart, name, material.uid)
			true
		} else false
	}


	protected def discharging(from: ActorRef, at: Long): Processing = {
		var result: Processing = allLeftEndpoints.head._2.restoringResource(from, at)
		for (ch <- allLeftEndpoints.tail) result = result orElse ch._2.restoringResource(from, at)
		result
	}
}
