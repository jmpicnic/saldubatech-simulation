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

package com.saldubatech.equipment.lift

import akka.actor.{ActorRef, Props}
import com.saldubatech.physics.Geography.{LinearGeography, LinearPoint}
import com.saldubatech.base.Processor.{ConfigureOwner, ExecutionResource, Task}
import com.saldubatech.base.{CarriagePhysics, DirectedChannel, Material, ProcessorHelper}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.equipment.elements.XSwitchTransfer
import com.saldubatech.equipment.elements.XSwitchTransfer.{RouteExecutionCommand, Transfer}
import com.saldubatech.physics.TaggedGeography
import com.saldubatech.utils.Boxer._

import scala.languageFeature.postfixOps

object LiftExecutor {
	type Engine = XSwitchTransfer[RouteExecutionCommand, ExecutionResource, LinearPoint]

	def apply(name: String,
	          physics: CarriagePhysics,
	          inbound: DirectedChannel[Material],
	          outbound: DirectedChannel[Material],
	          levelConnectors: Array[(DirectedChannel[Material], DirectedChannel[Material])],
	          initialLevel: DirectedChannel.Endpoint[Material],
	          ioLevel: Long = 0)(implicit gw: Gateway): ActorRef = {
		gw.simActorOf(Props(new LiftExecutor(name, physics, inbound, outbound, levelConnectors, initialLevel, ioLevel)), name)
	}

	class Commander(inChannel: DirectedChannel.End[Material], outChannel: DirectedChannel.Start[Material]) {
		def inbound(to: DirectedChannel.Start[Material], loadId: Option[String]): Transfer = Transfer(inChannel, to, loadId)
		def outbound(from: DirectedChannel.End[Material], loadId: Option[String]): Transfer = Transfer(from, outChannel, loadId)
		def transfer(from: DirectedChannel.End[Material], to: DirectedChannel.Start[Material], loadId: Option[String]): Transfer = Transfer(from, to, loadId)
	}

}

class LiftExecutor(name: String,
                   physics: CarriagePhysics,
                   inbound: DirectedChannel[Material],
                   outbound: DirectedChannel[Material],
                   levelConnectors: Array[(DirectedChannel[Material], DirectedChannel[Material])],
                   initialLevel: DirectedChannel.Endpoint[Material],
                   ioLevel: Long = 0)(implicit gw: Gateway)
	extends SimActorImpl(name, gw)
		with ProcessorHelper[RouteExecutionCommand, ExecutionResource] {
	import com.saldubatech.equipment.lift.LiftExecutor._

	assert(inbound.end == initialLevel ||
		outbound.start == initialLevel ||
		levelConnectors.map {
			_._1.start
		}.contains(initialLevel) ||
		levelConnectors.map {
			_._2.end
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
			levelConnectors.foreach(
				e => {
					e._1.registerStart(this)
					e._2.registerEnd(this)
				}
			)
			inbound.registerEnd(this)
			outbound.registerStart(this)
			engine = Some(
				XSwitchTransfer(
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
		case cmd: Transfer =>
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
	                                                at: Long):
	Option[Task[RouteExecutionCommand, ExecutionResource]] = {
		if(pendingCommands nonEmpty) {
			val cmd = pendingCommands.head // FIFO
			val candidate = cmd match {case c: Transfer => availableMaterials.find(
				e =>
					e._2 == c.source && (c.loadId.isEmpty || c.loadId.head == e._1.uid))}
			if(candidate isDefined) Some(Task[RouteExecutionCommand, ExecutionResource](cmd,Map(candidate.!))(at))
			else None
		} else None
	}

	override protected def localInitiateTask(task: Task[RouteExecutionCommand, ExecutionResource], at: Long): Unit = {
		engine.!.initiateCmd(task.cmd, task.materials.head._1, at)
	}

	override protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}

	override protected def localFinalizeDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit =
		engine.!.finalizeDelivery(load, tick)
}