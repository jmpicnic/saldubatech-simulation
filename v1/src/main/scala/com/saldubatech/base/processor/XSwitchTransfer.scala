/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.base.processor

import akka.actor.ActorRef
import com.saldubatech.base._
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.layout.{Geography, TaggedGeography}
import com.saldubatech.base.resource.Resource
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.utils.Boxer._

object XSwitchTransfer {
	def apply[
	C <: XSwitchTransfer.RouteExecutionCommand,
	R <: Resource,
	M <: Material,
	TK <: Task[C,M,M,R],
	P <: Geography.Point[P]]
	(host: ProcessorHelper[C, R, M, M, TK],
	 physics: CarriagePhysics,
	 geography: TaggedGeography[DirectedChannel.Endpoint[M], P],
	 initialLevel: DirectedChannel.Endpoint[M],
	): XSwitchTransfer[C, R, M,  TK, P] =
		new XSwitchTransfer[C,R,M, TK, P](physics, geography, initialLevel)(host)

	class RouteExecutionCommand(name: Option[String] = java.util.UUID.randomUUID().toString.?) extends Task.ExecutionCommandImpl

	case class Transfer[M <: Material](source: DirectedChannel.End[M], destination: DirectedChannel.Start[M], loadId: Option[String])
		extends RouteExecutionCommand {
		def isSource(other: DirectedChannel.End[Material]): Boolean = source == other
		def isDestination(other: DirectedChannel.Start[Material]): Boolean = destination == other
		def isLoad(other: Material): Boolean = loadId.isEmpty || loadId.head == other.uid
	}
}

class XSwitchTransfer[
	C <: XSwitchTransfer.RouteExecutionCommand,
	R <: Resource,
	M <: Material,
	TK <: Task[C,M,M,R],
	P <: Geography.Point[P]	]
	(physics: CarriagePhysics,
	 geography: TaggedGeography[DirectedChannel.Endpoint[M], P],
	 initialLevel: DirectedChannel.Endpoint[M],
	)(implicit host: ProcessorHelper[C, R, M, M, TK]) {
	import XSwitchTransfer._

	def protocol(from: ActorRef, at: Long): Processing = {
		stage match {
			case Stage.PICKUP =>
				stage = Stage.TRANSFER
				completeStaging(from, at)
			case Stage.TRANSFER =>
				stage = Stage.DELIVER
				completeTravel(from, at)
			case Stage.DELIVER => nullProcessing // Should no longer happen
				//finalizeDelivery(from, at)
			case Stage.WAIT => { case None => throw new IllegalStateException("Cannot Process Messages through the protocol in WAIT state")}
		}
	}

	private object Stage extends Enumeration {
		val WAIT, PICKUP, TRANSFER, DELIVER = new Val()
	}
	private case class PickUp(source: DirectedChannel.End[M], destination: DirectedChannel.Start[M], load: M)
	private case class Deliver(destination: DirectedChannel.Start[M], load: M)
	private case class FinalizeDelivery(via: DirectedChannel.Start[M], load: M)

	private var stage: Stage.Value = Stage.WAIT
	private var currentLevel = initialLevel

	def initiateCmd(cmd: RouteExecutionCommand, load: M, at: Long)(implicit host: SimActor): Unit = {
		stage = Stage.PICKUP
		cmd match {
			case c: Transfer[M] if c.loadId.isEmpty || c.loadId.head == load.uid =>
				PickUp(c.source, c.destination, load) ~> host.self in ((at, physics.timeToStage(geography.distance(currentLevel, c.destination))))
		}
	}

	private def completeStaging(from: ActorRef, at: Long): Processing = {
		case PickUp(source, destination, load) if host.self == from =>
			currentLevel = source
			stage = Stage.TRANSFER
			host.log.debug(s"Staged Load: $load from $source at $at")
			host.stageMaterial(load,source.?,at)
			Deliver(destination, load) ~> host.self in ((at, physics.timeToDeliver(geography.distance(currentLevel, destination))))
	}


	private def completeTravel(from: ActorRef, at: Long): Processing = {
		case Deliver(destination, load) if host.self == from =>
			stage = Stage.DELIVER
			currentLevel = destination
			host.tryDelivery(load,destination,at)
	}

	def finalizeDelivery(load: M, at: Long): Unit = {
		stage = Stage.WAIT
		host.completeCommand(Seq(load),at)
	}

}
