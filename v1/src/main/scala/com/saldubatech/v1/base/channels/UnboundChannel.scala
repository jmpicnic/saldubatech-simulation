/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.base.channels

import akka.actor.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.v1.ddes.SimActor
import com.saldubatech.v1.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimDSL._
import com.saldubatech.v1.base.layout.TaggedGeography.Tag
import com.saldubatech.v1.base.resource.DiscreteResourceBox
import com.saldubatech.util.Lang._
import com.typesafe.scalalogging.Logger

import scala.collection.mutable

object UnboundChannel {
	import Channel.TransferLoad

	def apply[L <: Identification](name: String = java.util.UUID.randomUUID().toString) =
		new UnboundChannel[L](name)

	trait Source[L <: Identification] extends Channel.Source[L, Start[L], End[L], Source[L], Sink[L]] {

		override protected val sourceProcessingBuilder: (ActorRef, Long) => Processing = (from, at) => nullProcessing
	}

	trait Sink[L <: Identification] extends Channel.Sink[L, End[L], Start[L], Source[L], Sink[L]] {
		def receiveMaterial(via: End[L], load: L, tick: Long): Unit

		override protected val sinkProcessingBuilder: (ActorRef, Long) => Processing = (from, at) =>
			inputs.values.map{endp => endp.loadReceiving(from, at)}.fold(nullProcessing){(acc, p) => acc orElse p}
	}

	trait Destination[L <: Identification] extends Channel.Destination[L, Start[L], End[L], Source[L], Sink[L]]

	class Start[L <: Identification](name: String)
		extends Channel.Start[L, Start[L], End[L], Source[L], Sink[L]](name) {

		override def sendLoad(load: L, at: Long, delay: Long = 0): Boolean = {
			owner.log.debug(s"$name sending Transfer Load ($load) from ${owner.uid} to ${peerOwner.!.path.name}")
			if(delay == 0) TransferLoad[L](name, load) ~> peerOwner.! now at
			else TransferLoad[L](name, load) ~> peerOwner.! in (at -> delay)
			true
		}
	}

	class End[L <: Identification](name: String)
		extends Channel.End[L, End[L], Start[L], Source[L], Sink[L]](name)
}

class UnboundChannel[L <: Identification](name:String = java.util.UUID.randomUUID().toString)
	extends Channel[L, UnboundChannel.End[L], UnboundChannel.Start[L], UnboundChannel.Source[L], UnboundChannel.Sink[L]] {

	override def startBuilder(name: String) = new UnboundChannel.Start(name)
	override def endBuilder(name: String) = new UnboundChannel.End(name)
}
