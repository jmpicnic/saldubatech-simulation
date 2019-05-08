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
import com.saldubatech.ddes.SimActorMixIn
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.resource.DiscreteResourceBox
import com.saldubatech.utils.Boxer._
import com.typesafe.scalalogging.Logger

import scala.collection.mutable

object AbstractChannel {
	case class TransferLoad[L <: Identification](channel: String, load: L, resource: String)
	case class AcknowledgeLoad[L <: Identification](channel: String, load: L, resource: String)
	case class ConfigureLeftEndpoints[C <: Identification](channels: Seq[C])
	case class ConfigureRightEndpoints[C <: Identification](channels: Seq[C])

	object FlowDirection extends Enumeration {
		val UPSTREAM = new Val()
		val DOWNSTREAM = new Val()

		implicit def SideToSmartSide(s: Value): Val = s.asInstanceOf[Val]

		class Val extends super.Val {
			def opposite: FlowDirection.Value = if(this == UPSTREAM) DOWNSTREAM else UPSTREAM
		}
	}
	/* This needs to move to a more general place */
	object GoodsFlow extends Enumeration {
		val INBOUND = new Val()
		val OUTBOUND = new Val()

		implicit def SideToSmartSide(s: Value): Val = s.asInstanceOf[Val]

		class Val extends super.Val {
			def opposite: GoodsFlow.Value = if(this == INBOUND) OUTBOUND else INBOUND
		}
	}

	trait Destination[L <: Identification, EP <: AbstractChannel.Endpoint[L, EP]]
		extends SimActorMixIn {
		val name: String
		def onAccept(via: EP, load: L, tick: Long): Unit
		def onRestore(via: EP, tick: Long): Unit

		def channelLeftConfiguring: Configuring = {
			case cmd: ConfigureLeftEndpoints[AbstractChannel[L, EP]] =>
				cmd.channels.foreach(c => configureLeftEndpoint(c))
		}
		// Separated to allow easy override
		protected def configureLeftEndpoint(c: AbstractChannel[L, EP]): Unit = {
			leftEndpoints.put(c.name, c.registerLeft(this))
		}
		def channelRightConfiguring: Configuring = {
			case cmd: ConfigureRightEndpoints[AbstractChannel[L, EP]] =>
				cmd.channels.foreach(c => configureRightEndpoint(c))
		}
		// Separated to allow easy override
		protected def configureRightEndpoint(c: AbstractChannel[L, EP]): Unit = {
			rightEndpoints.put(c.name, c.registerRight(this))
		}

		/*protected def processingBuilder = (from: ActorRef, tick: Long) =>
			rightEndpoints.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
				.orElse(leftEndpoints.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p))*/

		protected val leftEndpoints: mutable.Map[String, EP]= mutable.Map[String, EP]()
		protected val rightEndpoints: mutable.Map[String, EP] = mutable.Map[String, EP]()

		def leftEndpoint(endpointName: String): EP = leftEndpoints(endpointName)

		def rightEndpoint(endpointName: String): EP = rightEndpoints(endpointName)

		def allLeftEndpoints(): Map[String, EP] = leftEndpoints.toMap

		def allRightEndpoints(): Map[String, EP] = rightEndpoints.toMap
	}

	abstract class Endpoint[L <: Identification, EP <: AbstractChannel.Endpoint[L, EP]](name: String, side: FlowDirection.Value, sendingResources: DiscreteResourceBox, receivingResources: DiscreteResourceBox)
	extends Identification.Impl(name) with Tag {
		protected val sendingResourceBox: DiscreteResourceBox = DiscreteResourceBox(sendingResources.name+"::Sending", sendingResources.sourceAssets)
		protected val receivingResourceBox: DiscreteResourceBox = DiscreteResourceBox(receivingResources.name+"::Receiving", receivingResources.sourceAssets)
		private val inWip: mutable.Map[String, L] = mutable.Map()
		private val outWip: mutable.Map[L, String] = mutable.Map()
		private val logger = Logger("com.saldubatech.base.Channel")

		import com.saldubatech.ddes.SimDSL._

		implicit var owner: AbstractChannel.Destination[L, EP] = _
		var peerOwner: Option[ActorRef] = None
		//protected implicit lazy val host: SimActorMixIn = owner.!

		def myself: EP


		// Called by owners
		def sendLoad(load: L, at: Long): Boolean = {
			//Get resource
			val resource: Option[String] = sendingResourceBox.checkoutOne()
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


		def doneWithLoad(load: L, at: Long): Unit = {
			assert (outWip contains load, s"Load $load not accepted through this channel ($name)")
			logger.debug(s"Acknowledging load $load through $name")
			val resource = outWip(load)
			receivingResourceBox.checkin(resource)
			outWip remove load
			pendingLoads -= load
			owner.log.debug(s"$name Sending Restore $resource for ${load.uid} to ${peerOwner.!.path.name} at: $at")
			//owner.! send AcknowledgeLoad(name, load, resource) _to peerOwner.! now at
			AcknowledgeLoad(name, load, resource) ~> peerOwner.! now at
		}

		// For owners to include in their protocol
		def restoringResource(from: ActorRef, tick: Long): Processing = {
			case AcknowledgeLoad(channelName, _, token) if from == peerOwner.!  && channelName == name =>
				owner.log.debug(s"<${owner.self.path.name}> Restoring resource from ${peerOwner.!.path.name} on $channelName")
				doRestoreResource(from, tick, token)
		}

		def loadReceiving(from: ActorRef, tick: Long): Processing = {
			case c: TransferLoad[L] if from == peerOwner.!  && c.channel == name =>
				owner.log.debug(s"Received TransferLoad by $name with peer (${peerOwner.!.path.name} " +
					s"from ${from.path.name} via ${c.channel}")
				doLoadReceiving(from, tick, c.load, c.resource)
		}

		// Internal logic to allow overrides
		def doRestoreResource(from: ActorRef, tick: Long, resource: String): Unit = {
			owner.log.debug(s"$name processing Restore for token: $resource at $tick")
			if (inWip.contains(resource)) {
				val load = inWip(resource)
				sendingResourceBox.checkin(resource)
				inWip -= resource//inWip remove resource
				owner.onRestore(myself, tick)
				owner.log.debug(s"$name Completed processing Restore")
			}	else throw new IllegalArgumentException(s"Resource $resource not in current IN-WIP in $name, Received Restore from ${from.path.name}")
		}

		private val pendingLoads: mutable.ArrayBuffer[L] = mutable.ArrayBuffer.empty
		def doLoadReceiving(from: ActorRef, tick: Long, load: L, resource: String): Unit = {
			owner.log.debug(s"$name receiving Accept msg with job: $load at $tick")
			// Reserve the resource, register inWip and send for processing.
			val reserveSuccess = receivingResourceBox.reserve(resource)
			assert(reserveSuccess, s"Received resource $resource should be part of the receiving box ${receivingResourceBox.sourceAssets}")
			outWip.put(load, resource)
			pendingLoads.append(load)
			owner.onAccept(myself, load, tick)
			owner.log.debug(s"$name completed accept msg with job: $load")
		}
		def peekNextDeliveredLoad: Option[L] = pendingLoads.headOption
		def popNextDeliveredLoad(at: Long): Option[L] =
			if(pendingLoads nonEmpty) {
				val result = pendingLoads.headOption
				doneWithLoad(result.!, at)
				result
			}	else None

		override def toString: String = s"$name::$side"
	} // End Endpoint
}



abstract class
AbstractChannel[L <: Identification,EP <: AbstractChannel.Endpoint[L, EP]](
	                                                               val capacity: Int,
	                                                               val name:String = java.util.UUID.randomUUID().toString)
	extends Identification {
	import AbstractChannel._

	protected def givenId: Option[String] = name.?

	protected val resources: Map[FlowDirection.Value, DiscreteResourceBox] = Map(
		FlowDirection.UPSTREAM -> DiscreteResourceBox(name+"LR", capacity),
		FlowDirection.DOWNSTREAM -> DiscreteResourceBox(name+"RL", capacity)
	)

	private var leftRegistered = false
	private var rightRegistered = false

	val leftEndpoint: EP = buildEndpoint(FlowDirection.UPSTREAM)
	val rightEndpoint: EP = buildEndpoint(FlowDirection.DOWNSTREAM)

	protected def buildEndpoint(side: FlowDirection.Value): EP

	// Configuration by owners.
	def registerLeft(owner: AbstractChannel.Destination[L, EP]): EP = {
		assert(!leftRegistered)
		owner.log.debug(s"Configuring Left endpoint for $name with ${owner.name}")
		leftRegistered = true
		leftEndpoint.owner = owner
		if(rightEndpoint.owner != null) {
			leftEndpoint.peerOwner = rightEndpoint.owner.self.?
			rightEndpoint.peerOwner = owner.self.?
		}
		leftEndpoint
	}

	def registerRight(owner: AbstractChannel.Destination[L, EP]): EP = {
		assert(!rightRegistered)
		owner.log.debug(s"Configuring Right endpoint for $name with ${owner.name}")
		rightRegistered = true
		rightEndpoint.owner = owner
		if(leftEndpoint.owner != null) {
			rightEndpoint.peerOwner = leftEndpoint.owner.self.?
			leftEndpoint.peerOwner = owner.self.?
		}
		rightEndpoint
	}

}
