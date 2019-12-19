/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.base.channels

import akka.actor.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.base.layout.TaggedGeography.Tag
import com.saldubatech.util.Lang._
import com.typesafe.scalalogging.Logger

import scala.collection.mutable

object Channel{
	case class TransferLoad[L <: Identification](channel: String, load: L, resource: Option[String] = None)
	case class AcknowledgeLoad[L <: Identification](channel: String, load: L, resource: Option[String] = None)
	case class ConfigureStarts[C <: Identification](channels: Seq[C])
	case class ConfigureEnds[C <: Identification](channels: Seq[C])

	trait Source[L <: Identification, S <: Start[L, S, E, SO, SI], E <: End[L, E, S, SO, SI], SO <: Source[L, S, E, SO, SI],
	SI <: Sink[L,E,S,SO,SI]] extends SimActor {

		private def THE: SO = this.asInstanceOf[SO]

		def channelStartConfiguring: Configuring = {
			case cmd: ConfigureStarts[Channel[L, E, S, SO, SI]] =>
				cmd.channels.foreach(c => configureStart(c))
		}
		// Separated to allow easy override

		private def configureStart(c: Channel[L, E, S, SO, SI]): Unit = {
			outputs.put(c.uid, c.registerStart(THE))
		}

		protected val outputs: mutable.Map[String, S] = mutable.Map.empty
		def output(endpointName: String): S = outputs(endpointName)
		def allOutputs(): Map[String, S] = outputs.toMap

		protected val sourceProcessingBuilder: (ActorRef, Long) => Processing
	}

	trait Sink[L <: Identification, E <: End[L, E, S, SO, SI], S <: Start[L, S, E, SO, SI], SO <: Source[L, S, E, SO, SI],
	SI <: Sink[L,E,S,SO,SI]] extends SimActor {

		def THE: SI = this.asInstanceOf[SI]

		def receiveMaterial(via: E, load: L, tick: Long): Unit

		def channelEndConfiguring: Configuring = {
			case cmd: ConfigureEnds[Channel[L, E, S, SO, SI]] =>
				cmd.channels.foreach(c => configureEnd(c))
		}
		// Separated to allow easy override
		private def configureEnd(c: Channel[L, E, S, SO, SI]): Unit = {
			inputs.put(c.uid, c.registerEnd(THE))
		}
		def input(endpointName: String): E = inputs(endpointName)
		def allInputs(): Map[String, E] = inputs.toMap

		protected val inputs: mutable.Map[String, E]= mutable.Map.empty

		protected val sinkProcessingBuilder: (ActorRef, Long) => Processing = (from, at) =>
			inputs.values.map{endp => endp.loadReceiving(from, at)}.fold(nullProcessing){(acc, p) => acc orElse p}
	}

	trait Destination[L <: Identification, S <: Start[L, S, E, SO, SI], E <: End[L, E, S, SO, SI], SO <: Source[L, S, E, SO, SI],
	SI <: Sink[L,E,S,SO,SI]]
		extends Sink[L, E, S, SO, SI] {
		protected val processingBuilder: (ActorRef, Long) => Processing =
			(from, tick) => sinkProcessingBuilder(from, tick)
	}

	trait Endpoint[L <: Identification]
		extends Identification with Tag {
		protected lazy val logger = Logger(s"${getClass.toString}.$uid")
		var peerOwner: Option[ActorRef] = None
	}

	class EndpointImpl[L <: Identification](name: String) extends Endpoint[L] {
		override protected def givenId: Option[String] = name.?
	}

	abstract class Start[L <: Identification, S <: Start[L, S, E, SO, SI], E<: End[L, E, S, SO, SI], SO <: Source[L, S, E, SO, SI],
	SI <: Sink[L,E,S,SO,SI]](name: String)
		extends EndpointImpl[L](name) {
		implicit var owner: SO = _

		def sendLoad(load: L, at: Long, delay: Long = 0): Boolean
	}

	class End[L <: Identification, E <: End[L, E, S, SO, SI], S <: Start[L, S, E, SO, SI], SO <: Source[L, S, E, SO, SI],
	SI <: Sink[L,E,S,SO,SI]](name: String)
		extends EndpointImpl[L](name) {
		implicit var owner: SI = _

		private def THE: E = this.asInstanceOf[E]

		def loadReceiving(from: ActorRef, tick: Long): Processing = {
			case c: TransferLoad[L] if from == peerOwner.!  && c.channel == name =>
				owner.log.debug(s"Received TransferLoad by $name with peer (${peerOwner.!.path.name} " +
					s"from ${from.path.name} via ${c.channel}")
				doLoadReceiving(from, tick, c.load)
		}

		protected def doLoadReceiving(from: ActorRef, tick: Long, load: L): Unit = {
			owner.log.debug(s"$name receiving Accept msg with job: $load at $tick")
			owner.receiveMaterial(THE, load, tick)
		}
	}
}

abstract class Channel[
L <: Identification,
E <: Channel.End[L, E, S, SO, SI],
S <: Channel.Start[L, S, E, SO, SI],
SO <: Channel.Source[L, S, E, SO, SI],
SI <: Channel.Sink[L, E, S, SO, SI]](name:String = java.util.UUID.randomUUID().toString)
	extends Identification.Impl(name) {

	private var startRegistered = false
	private var endRegistered = false

	protected def startBuilder(name: String): S
	protected def endBuilder(name: String): E

	lazy val start: S = startBuilder(uid)
	lazy val end: E = endBuilder(uid)


	// Configuration by owners.
	def registerStart(owner: SO): S = {
		assert(!startRegistered, s"Trying to register Start owner: $owner, when already registered to: ${start.owner}")
		owner.log.debug(s"Configuring Start endpoint for $name with ${owner.uid}")
		startRegistered = true
		start.owner = owner
		if(end.owner != null) {
			start.peerOwner = end.owner.self.?
			end.peerOwner = start.owner.self.?
		}
		start
	}

	def registerEnd(owner: SI): E = {
		assert(!endRegistered)
		owner.log.debug(s"Configuring End endpoint for $name with ${owner.uid}")
		endRegistered = true
		end.owner = owner
		if(start.owner != null) {
			end.peerOwner = start.owner.self.?
			start.peerOwner = end.owner.self.?
		}
		end
	}
}
