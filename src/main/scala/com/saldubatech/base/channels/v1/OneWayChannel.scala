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

package com.saldubatech.base.channels.v1

import akka.actor.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.base.channels.v1.AbstractChannel.FlowDirection
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.resource.DiscreteResourceBox

object OneWayChannel {
	def apply[L <: Identification](capacity: Int, name: String = java.util.UUID.randomUUID().toString) =
		new OneWayChannel[L](capacity, name)

	trait Destination[L <: Identification] extends AbstractChannel.Destination[L, OneWayChannel.Endpoint[L]] {
		protected def processingBuilder: (ActorRef, Long) => Processing = (from: ActorRef, tick: Long) =>
			rightEndpoints.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
				.orElse(leftEndpoints.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p))
	}

	class Endpoint[L <: Identification](name: String, side: FlowDirection.Value, sendingResources: DiscreteResourceBox, receivingResources: DiscreteResourceBox)
		extends AbstractChannel.Endpoint[L, OneWayChannel.Endpoint[L]](name, side, sendingResources, receivingResources) {
		override def myself: OneWayChannel.Endpoint[L] = this
		override def sendLoad(load: L, at: Long): Boolean = {
			if(side == FlowDirection.UPSTREAM) super.sendLoad(load, at)
			else false
		}
	}

}

class OneWayChannel[L <: Identification](capacity: Int,
                                         name:String = java.util.UUID.randomUUID().toString)
	extends AbstractChannel[L, OneWayChannel.Endpoint[L]](capacity, name)
{
	override protected def buildEndpoint(side: AbstractChannel.FlowDirection.Value): OneWayChannel.Endpoint[L] =
		new OneWayChannel.Endpoint(name, side, resources(side), resources(side.opposite))
}
