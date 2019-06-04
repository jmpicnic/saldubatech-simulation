/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.shuttle

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.layout.Geography.{LinearGeography, LinearPoint}
import com.saldubatech.base.layout.TaggedGeography
import com.saldubatech.base.processor.Processor.ConfigureOwner
import com.saldubatech.base.processor.XSwitchTransfer.Transfer
import com.saldubatech.base.processor.{ProcessorHelper, Task, XSwitchTransfer}
import com.saldubatech.base.resource.Slot
import com.saldubatech.base.{CarriagePhysics, Material}
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.utils.Boxer._

import scala.collection.mutable
import scala.languageFeature.postfixOps

object LiftExecutor {
	type Engine = XSwitchTransfer[Transfer[Material], Slot[Material], Material, LiftTask, LinearPoint]

	def apply(name: String,
	          physics: CarriagePhysics,
	          inbound: DirectedChannel[Material],
	          outbound: DirectedChannel[Material],
	          levelConnectors: List[(DirectedChannel[Material], DirectedChannel[Material])],
	          initialLevel: DirectedChannel.Endpoint[Material],
	          ioLevel: Long = 0)(implicit gw: Gateway): ActorRef = {
		gw.simActorOf(Props(new LiftExecutor(name, physics, inbound, outbound, levelConnectors, initialLevel, ioLevel)), name)
	}
	case class LiftTask(override val cmd: Transfer[Material],
	                    override val initialMaterials: Map[Material, DirectedChannel.End[Material]],
	                    override val resource: Option[Slot[Material]] = None)(implicit createdAt: Long)
		extends Task[Transfer[Material], Material, Material, Slot[Material]](cmd, initialMaterials, resource) {

		override def isAcceptable(mat: Material): Boolean = true
		override protected def canStart(at: Long): Boolean = true
		override protected def canCompletePreparations(at: Long): Boolean = true
		override protected def canComplete(at: Long): Boolean = resource.nonEmpty && resource.head.slot.nonEmpty
	}

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
		with ProcessorHelper[Transfer[Material], Slot[Material], Material, Material, LiftExecutor.LiftTask] {
	import LiftExecutor._

	assert(inbound.end == initialLevel ||
		outbound.start == initialLevel ||
		levelConnectors.map {case (in, out) => in.start}.contains(initialLevel) ||
		levelConnectors.map {case (in, out) => out.end}.contains(initialLevel),
		s"Initial Level ($initialLevel) should be part of the provided endpoints")

	lazy val carriage: Slot[Material] = Slot[Material]()
	override protected def resource: Slot[Material] = carriage

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
				case (intoLevel, outOfLevel) =>
					intoLevel.registerStart(this)
					outOfLevel.registerEnd(this)
			}
			inbound.registerEnd(this)
			outbound.registerStart(this)
			engine = Some(
				XSwitchTransfer[Transfer[Material], Slot[Material], Material, LiftTask, LinearPoint](
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
		case cmd @ Transfer(_,_,_) => receiveCommand(cmd, at)
	}

	override def process(from: ActorRef, at: Long): Processing =
		engine.!.protocol(from, at) orElse
			commandReceiver(from, at) orElse
			outbound.start.restoringResource(from, at) orElse
			inbound.end.loadReceiving(from, at) orElse
			levelConnectors.map(ep => ep._1.start.restoringResource(from, at) orElse ep._2.end.loadReceiving(from, at))
				.fold(nullProcessing)((acc, el) => acc orElse el)

	override protected def consumeMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Boolean = false

	override protected def collectMaterialsForCommand(cmd: Transfer[Material], resource: Slot[Material],
	                                                  available: mutable.Map[Material, DirectedChannel.End[Material]])
	:Map[Material, DirectedChannel.End[Material]] =
		available.map{case (m, v) if v == cmd.source => m -> v}.toMap

	override protected def newTask(cmd: Transfer[Material],
	                               materials: Map[Material, DirectedChannel.End[Material]],
	                               resource: Slot[Material], at: Long): Option[LiftTask] =
		(cmd match {
			case Transfer(source, destination, loadId) => materials.find{
				case (mat, via) =>
					(via == source) && (loadId.isEmpty || loadId.head == mat.uid)
			}}).map(mat => LiftTask(cmd, Map(mat), carriage.?)(at))

	override protected def triggerTask(task: LiftTask, at: Long): Unit =
		engine.!.initiateCmd(task.cmd, task.initialMaterials.head._1, at)

	override protected def loadOnResource(tsk: LiftTask, material: Option[Material],
	                                      via: Option[DirectedChannel.End[Material]], at: Long): Unit = {
		assert(tsk.resource.! == carriage, s"Only resource available is $carriage")
		assert(carriage << material.!, s"Carriage $carriage should be able to accept material")
	}

	override protected def offloadFromResource(resource: Option[Slot[Material]]): Unit = {
		assert(resource.! == carriage, s"Only resource available is $carriage")
		assert(carriage.>> isDefined, s"Carriage $carriage was already empty")
	}

	override protected def finalizeTask(load: Material,
	                                    via: DirectedChannel.Start[Material], tick: Long): Unit =
		engine.!.finalizeDelivery(load, tick)

}
