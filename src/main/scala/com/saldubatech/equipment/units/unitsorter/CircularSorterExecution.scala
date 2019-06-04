/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.equipment.units.unitsorter

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.layout.Geography.ClosedPathPoint
import com.saldubatech.base.layout.TaggedGeography
import com.saldubatech.base.processor.Processor.ConfigureOwner
import com.saldubatech.base.processor.{MultiProcessorHelper, Task}
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.base.processor.XSwitchTransfer.Transfer
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object CircularSorterExecution {
	def apply(name: String,
	          inducts: List[DirectedChannel[Material]],
	          discharges: List[DirectedChannel[Material]],
	          geography: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint],
	          physics: CircularPathPhysics
	         )(implicit gw: Gateway): ActorRef =
		gw.simActorOf(Props(new CircularSorterExecution(name, inducts, discharges, geography, physics)), name)

	object CircularSorterTask{
		def apply(command: Transfer[Material], tote: Map[Material, DirectedChannel.End[Material]], tray: Option[Tray])
		         (implicit createdAt: Long)
		: Option[CircularSorterTask] = {
			if(tote.isEmpty || tray.isEmpty) None
			else new CircularSorterTask(command, tote, tray).?
		}
	}
	class CircularSorterTask(val command: Transfer[Material],
	                         val tote: Map[Material, DirectedChannel.End[Material]],
	                         val tray: Option[Tray])(implicit createdAt: Long)
		extends Task[Transfer[Material], Material, Material, Tray](command, tote, tray) {
		assert(tote nonEmpty, "No Materials passed to creation nof CircularSorterTask")
		override def isAcceptable(mat: Material): Boolean = true

		override protected def canStart(at: Long): Boolean = true

		override protected def canCompletePreparations(at: Long): Boolean = true

		override protected def canComplete(at: Long): Boolean = tray.nonEmpty && tray.head.slot.nonEmpty
	}
}

class CircularSorterExecution(val name: String,
                              inducts: List[DirectedChannel[Material]],
                              discharges: List[DirectedChannel[Material]],
                              geography: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint],
                              physics: CircularPathPhysics
                             )(implicit gw: Gateway)
extends SimActorImpl(name, gw) with MultiProcessorHelper[Transfer[Material], Tray, Material, Material, CircularSorterExecution.CircularSorterTask] {

	import CircularSorterExecution._
	import com.saldubatech.base.layout.Geography._

	private lazy val trays: List[Tray] = (0 until physics.nTrays).map(new Tray(_)).toList
	override protected def resources: Map[String, Tray] = trays.map(tr => tr.uid -> tr).toMap

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			inducts.foreach(_.registerEnd(this))
			discharges.foreach(_.registerStart(this))
	}

	override protected def updateState(at: Long): Unit =
		physics.updateLocation(at)

	private def commandReceiver(from: ActorRef, at: Long): Processing = {
		case cmd @ Transfer(src,dest,load) =>
			receiveCommand(cmd, at)
	}

	override def process(from: ActorRef, at: Long): Processing =
		stagingProcessing(from, at) orElse
			finalizeProcessing(from, at) orElse
			commandReceiver(from, at) orElse
			inducts.map(_.end.loadReceiving(from, at)).fold(nullProcessing)((acc, el) => acc orElse el) orElse
			discharges.map(_.start.restoringResource(from, at)).fold(nullProcessing)((acc, el) => acc orElse el)

	override protected def consumeMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Boolean = false

	override protected def findResource(cmd: Transfer[Material], resources: mutable.Map[String, Tray]): Option[(Transfer[Material], String, Tray)] =
		if (resources isEmpty) None else resources.minBy(t => distance(t._2, cmd.source)).?.map(t => (cmd, t._1, t._2))

	override protected def collectMaterialsForCommand(cmd: Transfer[Material], resource: Tray,
	                                                  available: mutable.Map[Material, DirectedChannel.End[Material]])
	: Map[Material, DirectedChannel.End[Material]] = {
		available.filter(t => t._2 == cmd.source).toMap
	}

	override protected def newTask(cmd: Transfer[Material],
	                               materials: Map[Material, DirectedChannel.End[Material]],
	                               resource: Tray, at: Long)
	: Option[CircularSorterTask] = {
		if(materials nonEmpty) log.debug(s"Creating task for ${materials.head}")
		CircularSorterTask(cmd, materials, resource.?)(at)
	}

	override protected def triggerTask(task: CircularSorterTask, at: Long): Unit = {
		assert(task.resource isDefined, "Must have a Tray defined as resource")
		val timeToPickUp =
			physics.estimateElapsedFromNumber(task.resource.!.number,
				geography.location(task.cmd.source))
		Induct(task) ~> self in ((at, timeToPickUp))
	}

	override protected def loadOnResource(task: CircularSorterTask, material: Option[Material],
	                                      via: Option[DirectedChannel.End[Material]], at: Long): Unit =
		task.resource.! << material.!

	override protected def offloadFromResource(resource: Option[Tray]): Unit = resource.!.>>

	def distance(t: Tray, v: DirectedChannel.End[Material]): Long = {
		val tIndex: ClosedPathPoint = physics.indexForElement(t.number)
		geography.distance(tIndex, v)
	}

	override protected def finalizeTask(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit = {
		//val trayNumber = physics.pointAtIndex(geography.location(via))
		//trays(trayNumber.coord.toInt).>> // Empty the tray, now Handled by offloadFromResource
		completeCommand(cmdId, Seq(load), tick)
	}
	private case class Induct(task: Task[Transfer[Material], Material, Material, Tray])
	private case class Discharge(task: Task[Transfer[Material], Material, Material, Tray])


	private class CloserTrayCompare extends Ordering[CircularSorterTask] {
		override def compare(x: CircularSorterTask, y: CircularSorterTask): Int = {
			(distance(y.resource.!, y.cmd.source) - distance(x.resource.!, x.cmd.source)).toInt
		}
	}

	private def stagingProcessing(from: ActorRef, at: Long): Processing = {
		case Induct(task) =>
			physics.updateLocation(at)
			stageMaterial(task.cmd.uid, task.initialMaterials.head._1, task.cmd.source.?, at)
			val timeToDischarge =
				physics.estimateElapsedFromNumber(task.resource.!.number,
					geography.location(task.cmd.destination))
			Discharge(task) ~> self in ((at, timeToDischarge))
	}

	private def finalizeProcessing(from: ActorRef, at: Long): Processing = {
		case Discharge(task) =>
			physics.updateLocation(at)
			tryDelivery(task.cmd.uid, task.initialMaterials.head._1, task.cmd.destination, at)
	}

}
