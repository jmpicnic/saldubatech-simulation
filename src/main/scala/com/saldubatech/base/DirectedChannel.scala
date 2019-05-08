/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.physics.TaggedGeography.Tag
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.SimActorMixIn
import com.saldubatech.ddes.SimActorMixIn.nullProcessing
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.resource.DiscreteResourceBox
import com.typesafe.scalalogging.Logger

import com.saldubatech.utils.Boxer._
import scala.collection.mutable

object DirectedChannel {
	case class TransferLoad[L <: Identification](channel: String, load: L, resource: String)
	case class AcknowledgeLoad[L <: Identification](channel: String, load: L, resource: String)
	case class ConfigureStarts[C <: Identification](channels: Seq[C])
	case class ConfigureEnds[C <: Identification](channels: Seq[C])

	def apply[L <: Identification](capacity: Int, name: String = java.util.UUID.randomUUID().toString) =
		new DirectedChannel[L](capacity, name)

	trait Destination[L <: Identification]
		extends SimActorMixIn {
		val name: String
		def onAccept(via: End[L], load: L, tick: Long): Unit
		def onRestore(via: Start[L], tick: Long): Unit

		protected def processingBuilder: (ActorRef, Long) => Processing = (from, tick) =>
			outputs.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
				.orElse(inputs.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p))

		def channelStartConfiguring: Configuring = {
			case cmd: ConfigureStarts[DirectedChannel[L]] =>
				cmd.channels.foreach(c => configureStart(c))
		}
		// Separated to allow easy override

		private def configureStart(c: DirectedChannel[L]): Unit = {
			outputs.put(c.uid, c.registerStart(this))
		}
		def channelEndConfiguring: Configuring = {
			case cmd: ConfigureEnds[DirectedChannel[L]] =>
				cmd.channels.foreach(c => configureEnd(c))
		}
		// Separated to allow easy override
		private def configureEnd(c: DirectedChannel[L]): Unit = {
			inputs.put(c.uid, c.registerEnd(this))
		}

		protected val inputs: mutable.Map[String, End[L]]= mutable.Map.empty
		protected val outputs: mutable.Map[String, Start[L]] = mutable.Map.empty

		def input(endpointName: String): End[L] = inputs(endpointName)

		def output(endpointName: String): Start[L] = outputs(endpointName)

		def allInputs(): Map[String, End[L]] = inputs.toMap

		def allOutputs(): Map[String, Start[L]] = outputs.toMap
	}

	class Endpoint[L <: Identification](name: String, channelResources: DiscreteResourceBox)
		extends Identification.Impl(name)
			with Tag {
		protected lazy val logger = Logger(s"${getClass.toString}.$name")

		implicit var owner: DirectedChannel.Destination[L] = _
		var peerOwner: Option[ActorRef] = None
		protected val resources = DiscreteResourceBox(channelResources)

	}

	class Start[L <: Identification](name: String, sendingResourceBox: DiscreteResourceBox)
		extends Endpoint[L](name, sendingResourceBox) {
		private val inWip: mutable.Map[String, L] = mutable.Map()

		def sendLoad(load: L, at: Long): Boolean = {
			//Get resource
			val resource: Option[String] = resources.checkoutOne()
			if (resource.isDefined) {
				logger.debug(s"Sending load $load through $name")
				inWip.put(resource get, load)
				owner.log.debug(s"$name In Endpoint Acquiring(${inWip.size}): ${resource.get}")
				owner.log.debug(s"$name sending Transfer Load ($load) from ${owner.name} to ${peerOwner.!.path.name}")
				//owner.! send TransferLoad[L](name, load, resource get) _to peerOwner.! now at
				TransferLoad[L](name, load, resource get) ~> peerOwner.! now at
				true
			} else false
		}

		// For owners to include in their protocol
		def restoringResource(from: ActorRef, tick: Long): Processing = {
			case AcknowledgeLoad(channelName, _, token) if from == peerOwner.!  && channelName == name =>
				owner.log.debug(s"<${owner.self.path.name}> Restoring resource from ${peerOwner.!.path.name} on $channelName")
				doRestoreResource(from, tick, token)
		}

		// Internal logic to allow overrides
		def doRestoreResource(from: ActorRef, tick: Long, resource: String): Unit = {
			owner.log.debug(s"$name processing Restore for token: $resource at $tick")
			if (inWip.contains(resource)) {
				val load = inWip(resource)
				resources.checkin(resource)
				inWip -= resource//inWip remove resource
				owner.onRestore(this, tick)
				owner.log.debug(s"$name Completed processing Restore")
			}	else throw new IllegalArgumentException(s"Resource $resource not in current IN-WIP in $name, Received Restore from ${from.path.name}")
		}
	}

	class End[L <: Identification](name: String, receivingResourceBox: DiscreteResourceBox)
		extends Endpoint[L](name, receivingResourceBox) {
		private val outWip: mutable.Map[L, String] = mutable.Map()

		private val available: mutable.ListBuffer[L] = mutable.ListBuffer()

		def headOption: Option[L] = available.headOption
		def reserve: Option[L] = {
			val result = headOption
			if(result isDefined) available -= result.!
			result
		}

		def doneWithLoad(load: L, at: Long): Unit = {
			assert (outWip contains load, s"Load $load not accepted through this channel ($name)")
			logger.debug(s"Acknowledging load $load through $name")
			val resource = outWip(load)
			resources.checkin(resource)
			outWip remove load
			if(available contains load) available -= load
			owner.log.debug(s"$name Sending Restore $resource for ${load.uid} to ${peerOwner.!.path.name} at: $at")
			//owner.! send AcknowledgeLoad(name, load, resource) _to peerOwner.! now at
			AcknowledgeLoad(name, load, resource) ~> peerOwner.! now at
		}

		def loadReceiving(from: ActorRef, tick: Long): Processing = {
			case c: TransferLoad[L] if from == peerOwner.!  && c.channel == name =>
				owner.log.debug(s"Received TransferLoad by $name with peer (${peerOwner.!.path.name} " +
					s"from ${from.path.name} via ${c.channel}")
				doLoadReceiving(from, tick, c.load, c.resource)
		}

		def doLoadReceiving(from: ActorRef, tick: Long, load: L, resource: String): Unit = {
			owner.log.debug(s"$name receiving Accept msg with job: $load at $tick")
			// Reserve the resource, register inWip and send for processing.
			val reserveSuccess = resources.reserve(resource)
			assert(reserveSuccess, s"Received resource $resource should be part of the receiving box ${resources.sourceAssets}")
			outWip.put(load, resource)
			available.append(load)
			owner.onAccept(this, load, tick)
			owner.log.debug(s"$name completed accept msg with job: $load")
		}
	}


}

class DirectedChannel[L <: Identification](capacity: Int,
                                         name:String = java.util.UUID.randomUUID().toString)
	extends Identification.Impl(name) {
	import DirectedChannel._

	protected val resources = DiscreteResourceBox(name, capacity)

	private var startRegistered = false
	private var endRegistered = false

	val start: Start[L] = new DirectedChannel.Start(name, resources)
	val end: End[L] = new DirectedChannel.End(name, resources)


	// Configuration by owners.
	def registerStart(owner: DirectedChannel.Destination[L]): Start[L] = {
		assert(!startRegistered)
		owner.log.debug(s"Configuring Start endpoint for $name with ${owner.name}")
		startRegistered = true
		start.owner = owner
		if(end.owner != null) {
			start.peerOwner = end.owner.self.?
			end.peerOwner = start.owner.self.?
		}
		start
	}

	def registerEnd(owner: DirectedChannel.Destination[L]): End[L] = {
		assert(!endRegistered)
		owner.log.debug(s"Configuring End endpoint for $name with ${owner.name}")
		endRegistered = true
		end.owner = owner
		if(start.owner != null) {
			end.peerOwner = start.owner.self.?
			start.peerOwner = end.owner.self.?
		}
		end
	}

}
