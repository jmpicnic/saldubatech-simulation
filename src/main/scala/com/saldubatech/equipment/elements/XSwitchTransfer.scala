/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.ActorRef
import com.saldubatech.base._
import com.saldubatech.ddes.SimActorMixIn
import com.saldubatech.ddes.SimActorMixIn.{Processing, nullProcessing}
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.utils.Boxer._

object XSwitchTransfer {
	def apply[
	C <: XSwitchTransfer.RouteExecutionCommand,
	R <: Processor.ExecutionResource,
	P <: Geography.Point[P]	]
	(host: ProcessorHelper.ProcessorHelperI[C, R],
	 physics: CarriagePhysics,
	 geography: Geography[DirectedChannel.Endpoint[Material], P],
	 initialLevel: DirectedChannel.Endpoint[Material],
	): XSwitchTransfer[C, R, P] = {
		new XSwitchTransfer[C,R,P](physics, geography, initialLevel)(host)
	}
	class RouteExecutionCommand(name: Option[String] = java.util.UUID.randomUUID().toString.?) extends Processor.ExecutionCommandImpl

	case class Transfer(source: DirectedChannel.End[Material], destination: DirectedChannel.Start[Material])
		extends RouteExecutionCommand

}

class XSwitchTransfer[
C <: XSwitchTransfer.RouteExecutionCommand,
R <: Processor.ExecutionResource,
P <: Geography.Point[P]](physics: CarriagePhysics,
                      geography: Geography[DirectedChannel.Endpoint[Material], P],
                      initialLevel: DirectedChannel.Endpoint[Material])(implicit host: ProcessorHelper.ProcessorSupport[C]) {
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
	private case class PickUp(source: DirectedChannel.End[Material], destination: DirectedChannel.Start[Material], load: Material)
	private case class Deliver(destination: DirectedChannel.Start[Material], load: Material)
	private case class FinalizeDelivery(via: DirectedChannel.Start[Material], load: Material)

	private var stage: Stage.Value = Stage.WAIT
	private var currentLevel = initialLevel

	def initiateCmd(cmd: RouteExecutionCommand, load: Material, at: Long)(implicit host: SimActorMixIn): Unit = {
		stage = Stage.PICKUP
		cmd match {
			case c: Transfer => PickUp(c.source, c.destination, load) ~> host.self in ((at, physics.timeToStage(geography.distance(currentLevel, c.destination))))
		}
	}

	private def completeStaging(from: ActorRef, at: Long): Processing = {
		case PickUp(source, destination, load) if host.self == from =>
			currentLevel = source
			stage = Stage.TRANSFER
			host.log.debug(s"Staged Load: $load from $source at $at")
			host.stageMaterial(load,source,at)
			Deliver(destination, load) ~> host.self in ((at, physics.timeToDeliver(geography.distance(currentLevel, destination))))
	}

	private def completeTravel(from: ActorRef, at: Long): Processing = {
		case Deliver(destination, load) if host.self == from =>
			stage = Stage.DELIVER
			currentLevel = destination
			host.tryDelivery(load,destination,at)
	}

	def finalizeDelivery(load: Material, at: Long): Unit = {
		stage = Stage.WAIT
		host.completeCommand(Seq(load),at)
	}

}
