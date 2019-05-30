/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.equipment.units.shuttle

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Processor.{ConfigureOwner, ExecutionResource, Task}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.layout.Geography.{LinearGeography, LinearPoint}
import com.saldubatech.base.layout.TaggedGeography
import com.saldubatech.base.{CarriagePhysics, Material, ProcessorHelper}
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.equipment.elements.XSwitchTransfer
import com.saldubatech.equipment.elements.XSwitchTransfer.{RouteExecutionCommand, Transfer}
import com.saldubatech.utils.Boxer._

import scala.languageFeature.postfixOps

object LiftExecutor {
	type Engine = XSwitchTransfer[RouteExecutionCommand, ExecutionResource, Material, LiftTask, LinearPoint]

	def apply(name: String,
	          physics: CarriagePhysics,
	          inbound: DirectedChannel[Material],
	          outbound: DirectedChannel[Material],
	          levelConnectors: List[(DirectedChannel[Material], DirectedChannel[Material])],
	          initialLevel: DirectedChannel.Endpoint[Material],
	          ioLevel: Long = 0)(implicit gw: Gateway): ActorRef = {
		gw.simActorOf(Props(new LiftExecutor(name, physics, inbound, outbound, levelConnectors, initialLevel, ioLevel)), name)
	}
	case class LiftTask(override val cmd: RouteExecutionCommand,
	                    override val materials: Map[Material, DirectedChannel.End[Material]],
	                    override val resource: Option[ExecutionResource] = None)(implicit createdAt: Long)
		extends Task[RouteExecutionCommand, Material, ExecutionResource](cmd, materials, resource)

	class Commander(inChannel: DirectedChannel.End[Material], outChannel: DirectedChannel.Start[Material]) {
		def inbound(to: DirectedChannel.Start[Material], loadId: Option[String]): Transfer[Material] = Transfer[Material](inChannel, to, loadId)
		def outbound(from: DirectedChannel.End[Material], loadId: Option[String]): Transfer[Material] = Transfer[Material](from, outChannel, loadId)
		def transfer(from: DirectedChannel.End[Material], to: DirectedChannel.Start[Material], loadId: Option[String]): Transfer[Material] = Transfer[Material](from, to, loadId)
	}

}

class LiftExecutor(name: String,
                   physics: CarriagePhysics,
                   inbound: DirectedChannel[Material],
                   outbound: DirectedChannel[Material],
                   levelConnectors: List[(DirectedChannel[Material], DirectedChannel[Material])],
                   initialLevel: DirectedChannel.Endpoint[Material],
                   ioLevel: Long = 0)(implicit gw: Gateway)
	extends SimActorImpl(name, gw)
		with ProcessorHelper[RouteExecutionCommand, ExecutionResource, Material, Material, LiftExecutor.LiftTask] {
	import LiftExecutor._

	assert(inbound.end == initialLevel ||
		outbound.start == initialLevel ||
		levelConnectors.map {
			case (in, out) => in.start
		}.contains(initialLevel) ||
		levelConnectors.map {
			case (in, out) => out.end
		}.contains(initialLevel),
		s"Initial Level ($initialLevel) should be part of the provided endpoints")

	//private var engine: Option[TransferExecution] = None
	private var engine: Option[Engine] = None

	val tags: Map[DirectedChannel.Endpoint[Material], LinearPoint] =
		Map[DirectedChannel.Endpoint[Material], LinearPoint](inbound.end -> ioLevel, outbound.start -> ioLevel) ++
			Map[DirectedChannel.Endpoint[Material], LinearPoint]((
				levelConnectors.map {
					_._1.start
				}.zipWithIndex ++
					levelConnectors.map {
						_._2.end
					}.zipWithIndex).map { case (k, v) => k -> new LinearPoint(v) }: _*)

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			levelConnectors.foreach {
				case (intoLevel, outOfLevel) => {
					intoLevel.registerStart(this)
					outOfLevel.registerEnd(this)
				}
			}
			inbound.registerEnd(this)
			outbound.registerStart(this)
			engine = Some(
				XSwitchTransfer[RouteExecutionCommand, ExecutionResource, Material, LiftTask, LinearPoint](
					this,
					physics,
					new TaggedGeography.Impl[DirectedChannel.Endpoint[Material], LinearPoint](tags, new LinearGeography()),
					initialLevel
				)
			)
	}

	override protected def updateState(at: Long): Unit = {
		// Nothing to update for now.
	}

	def commandReceiver(from: ActorRef, at: Long): Processing = {
		case cmd: Transfer[Material] =>
			receiveCommand(cmd, at)
	}

	override def process(from: ActorRef, at: Long): Processing =
		engine.!.protocol(from, at) orElse
			commandReceiver(from, at) orElse
			outbound.start.restoringResource(from, at) orElse
			inbound.end.loadReceiving(from, at) orElse
			levelConnectors.map(ep => ep._1.start.restoringResource(from, at) orElse ep._2.end.loadReceiving(from, at))
				.fold(nullProcessing)((acc, el) => acc orElse el)

	override protected def localSelectNextExecution(pendingCommands: List[RouteExecutionCommand],
	                                                availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                                                at: Long): Option[LiftTask] = {
		if(pendingCommands nonEmpty) {
			val cmd = pendingCommands.head // FIFO
			val candidate = cmd match {case c: Transfer[Material] => availableMaterials.find(
				e =>
					e._2 == c.source && (c.loadId.isEmpty || c.loadId.head == e._1.uid))}
			if(candidate isDefined) Some(LiftTask(cmd,Map(candidate.!))(at))
			else None
		} else None
	}

	override protected def localInitiateTask(task: LiftTask, at: Long): Unit = {
		engine.!.initiateCmd(task.cmd, task.materials.head._1, at)
	}

	override protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}

	override protected def localFinalizeDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit =
		engine.!.finalizeDelivery(load, tick)
}