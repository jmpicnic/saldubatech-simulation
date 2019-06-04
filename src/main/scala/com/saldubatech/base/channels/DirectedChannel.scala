/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.channels

import akka.actor.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.base.resource.DiscreteResourceBox
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.randomvariables.Distributions._
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object DirectedChannel {
	import com.saldubatech.base.channels.Channel.{AcknowledgeLoad, TransferLoad}

	def apply[L <: Identification](capacity: Int, name: String = java.util.UUID.randomUUID().toString,
	                               endPoints: Int = 1,
	                               delay: LongRVar = zeroLong) =
		new DirectedChannel[L](capacity, name, endPoints, delay)

	trait Source[L <: Identification] extends Channel.Source[L, Start[L], End[L], Source[L], Sink[L]] {
		def restoreChannelCapacity(via: Start[L], tick: Long): Unit

		protected val sourceProcessingBuilder: (ActorRef, Long) => Processing =
			(from, tick) => outputs.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
	}

	trait Sink[L <: Identification] extends Channel.Sink[L, End[L], Start[L], Source[L], Sink[L]] {

		override protected val sinkProcessingBuilder: (ActorRef, Long) => Processing = (from, at) =>
			inputs.values.map{endp => endp.loadReceiving(from, at)}.fold(nullProcessing){(acc, p) => acc orElse p}
	}

	trait Destination[L <: Identification]
		extends Sink[L] with Channel.Destination[L, Start[L], End[L], Source[L], Sink[L]]

	trait Endpoint[L <: Identification]
		extends Channel.Endpoint[L] {
		val channelResources: DiscreteResourceBox
		protected val resources = DiscreteResourceBox(channelResources)
	}

	class Start[L <: Identification](name: String, val channelResources: DiscreteResourceBox, delay: LongRVar = zeroLong)
		extends Channel.Start[L, Start[L], End[L], Source[L], Sink[L]](name) with Endpoint[L]{
		private val inWip: mutable.Map[String, L] = mutable.Map()
		//override def readResources: DiscreteResourceBox = _channelResources

		override def sendLoad(load: L, at: Long, sendDelay: Long = delay()): Boolean = {
			//Get resource
			val resource: Option[String] = resources.checkoutOne()
			if (resource.isDefined) {
				logger.debug(s"Sending load $load through $name")
				inWip.put(resource get, load)
				owner.log.debug(s"$name In Endpoint Acquiring(${inWip.size}): ${resource.get}")
				owner.log.debug(s"$name sending Transfer Load ($load) from ${owner.uid} to ${peerOwner.!.path.name}")
				//owner.! send TransferLoad[L](name, load, resource get) _to peerOwner.! now at
				if(sendDelay == 0) TransferLoad[L](name, load, resource) ~> peerOwner.! now at
				else TransferLoad[L](name, load, resource) ~> peerOwner.! in (at -> sendDelay)
				true
			} else false
		}

		// For owners to include in their protocol
		def restoringResource(from: ActorRef, tick: Long): Processing = {
			case AcknowledgeLoad(channelName, _, token) if from == peerOwner.!  && channelName == name =>
				owner.log.debug(s"<${owner.self.path.name}> Restoring resource from ${peerOwner.!.path.name} on $channelName")
				doRestoreResource(from, tick, token.!)
		}

		// Internal logic to allow overrides
		def doRestoreResource(from: ActorRef, tick: Long, resource: String): Unit = {
			owner.log.debug(s"$name processing Restore for token: $resource at $tick")
			if (inWip.contains(resource)) {
				val load = inWip(resource)
				resources.checkin(resource)
				inWip -= resource//inWip remove resource
				owner.restoreChannelCapacity(this, tick)
				owner.log.debug(s"$name Completed processing Restore")
			}	else throw new IllegalArgumentException(s"Resource $resource not in current IN-WIP in $name, Received Restore from ${from.path.name}")
		}
	}

	class End[L <: Identification](name: String, val channelResources: DiscreteResourceBox, nEndpoints: Int)
		extends Channel.End[L, End[L], Start[L], Source[L], Sink[L]](name) with Endpoint[L] {
		private val outWip: mutable.Map[L, String] = mutable.Map()

		private val pending: mutable.Queue[(ActorRef, L)] = mutable.Queue.empty

		def doneWithLoad(load: L, at: Long): Unit = {
			assert (outWip contains load, s"Load $load not accepted through this channel ($name)")
			val resource = outWip(load)
			resources.checkin(resource)
			outWip remove load
			owner.log.debug(s"$name Sending Restore $resource for ${load.uid} to ${peerOwner.!.path.name} at: $at, pending $pending")
			if(pending nonEmpty) {
				val (from, load) = pending.dequeue
				super.doLoadReceiving(from, at, load)
			}
			AcknowledgeLoad(name, load, resource.?) ~> peerOwner.! now at
		}

		override def loadReceiving(from: ActorRef, tick: Long): Processing = {
			case c: TransferLoad[L] if from == peerOwner.!  && c.channel == name =>
				owner.log.debug(s"Received TransferLoad with ${c.load} by $name with peer (${peerOwner.!.path.name} " +
					s"from ${from.path.name} via ${c.channel}")
				doLoadReceiving(from, tick, c.load, c.resource)
		}

		def doLoadReceiving(from: ActorRef, tick: Long, load: L, resource: Option[String]): Unit = {
			// Reserve the resource, register outWip and send for processing.
			val reserveSuccess = resources.reserve(resource.!)
			assert(reserveSuccess, s"Received resource $resource should be part of the receiving box ${resources.sourceAssets}")
			outWip.put(load, resource.!)
			//available.append(load)
			if(outWip.size <= nEndpoints)	{
				super.doLoadReceiving(from, tick, load)
			} else {
				pending.enqueue((from, load))
			}
//			owner.receiveMaterial(this, load, tick) now done in super
		}
	}
}

class DirectedChannel[L <: Identification](capacity: Int,
                                           name:String = java.util.UUID.randomUUID.toString,
                                           nEndpoints: Int = 1,
	                                           delay: LongRVar = zeroLong)
	extends Channel[L, DirectedChannel.End[L], DirectedChannel.Start[L], DirectedChannel.Source[L], DirectedChannel.Sink[L]](name) {

	protected val resources:DiscreteResourceBox = DiscreteResourceBox(name, capacity)

	override def startBuilder(name: String): DirectedChannel.Start[L] = {assert(resources != null, "resources must be initialized"); new DirectedChannel.Start[L](name, resources)}
	override def endBuilder(name: String): DirectedChannel.End[L] = {assert(resources != null, "resources must be initialized");new DirectedChannel.End[L](name, resources, nEndpoints)}
}
