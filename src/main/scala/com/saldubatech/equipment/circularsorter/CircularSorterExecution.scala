/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.circularsorter

import akka.actor.{ActorRef, Props}
import com.saldubatech.physics.{Geography, TaggedGeography}
import com.saldubatech.physics.Geography.ClosedPathPoint
import com.saldubatech.base.Processor.{ConfigureOwner, Task}
import com.saldubatech.base.{DirectedChannel, Material, MultiProcessorHelper}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.ddes.SimActorMixIn.nullProcessing
import com.saldubatech.ddes.{Gateway, SimActor}
import com.saldubatech.equipment.elements.XSwitchTransfer.Transfer
import com.saldubatech.utils.Boxer._
import com.saldubatech.ddes.SimDSL._

import scala.collection.mutable

object CircularSorterExecution {
	def apply(name: String,
	          inducts: List[DirectedChannel[Material]],
	          discharges: List[DirectedChannel[Material]],
	          geography: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint],
	          physics: CircularPathPhysics
	         )(implicit gw: Gateway): ActorRef =
		gw.simActorOf(Props(new CircularSorterExecution(name, inducts, discharges, geography, physics)), name)

}

class CircularSorterExecution(name: String,
                              inducts: List[DirectedChannel[Material]],
                              discharges: List[DirectedChannel[Material]],
                              geography: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint],
                              physics: CircularPathPhysics
                             )(implicit gw: Gateway)
extends SimActor(name, gw) with MultiProcessorHelper[Transfer, Tray]{
	import Geography._

	override val maxConcurrency: Int = physics.nTrays

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			inducts.foreach(_.registerEnd(this))
			discharges.foreach(_.registerStart(this))
	}

	override protected def updateState(at: Long): Unit = {
		physics.updateLocation(at)
	}

	private def commandReceiver(from: ActorRef, at: Long): Processing = {
		case cmd: Transfer =>
			receiveCommand(cmd, at)
	}

	override def process(from: ActorRef, at: Long): Processing =
		completeStaging(from, at) orElse completeMovement(from, at) orElse
			commandReceiver(from, at) orElse
			inducts.map(_.end.loadReceiving(from, at)).fold(nullProcessing)((acc, el) => acc orElse el) orElse
			discharges.map(_.start.restoringResource(from, at)).fold(nullProcessing)((acc, el) => acc orElse el)

	private val trays: List[Tray] = (0 until physics.nTrays).map(new Tray(_)).toList

	private def emptyTrays(): List[Tray] = trays.filter(t => t.isEmpty)

	def distance(t: Tray, v: DirectedChannel.End[Material]): Long = {
		val tIndex: ClosedPathPoint = physics.indexForElement(t.number)
		geography.distance(tIndex, v)
	}

	private class CloserTrayCompare extends Ordering[Task[Transfer, Tray]] {
		override def compare(x: Task[Transfer, Tray], y: Task[Transfer, Tray]): Int = {
			(distance(y.resource.!, y.cmd.source) - distance(x.resource.!, x.cmd.source)).toInt
		}
	}

	private def collectReadyCommands(pendingCommands: List[Transfer],
	                         availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                         at: Long): mutable.SortedSet[Task[Transfer, Tray]] = {
		val result: mutable.SortedSet[Task[Transfer, Tray]] = mutable.SortedSet()(new CloserTrayCompare())
		val empties: mutable.Set[Tray] = mutable.Set(trays.filter(_.isEmpty): _*)
		for(c <- pendingCommands) {
			for((m, v) <- availableMaterials) {
				if(c.source == v) {
					val t = empties.minBy(t => distance(t, v))
					log.debug(s"Selecting Tray ${t.number} at ${physics.indexForElement(t.number)} to pick from $v located at ${geography.location(v)}")
					result += Task(c, Map(m -> v), t.?)(at)
					empties -= t
				}
			}
		}
		result
	}

	override protected def localSelectNextTasks(pendingCommands: List[Transfer],
	                                            availableMaterials: Map[Material,
		                                            DirectedChannel.End[Material]], at: Long):
	Seq[Task[Transfer, Tray]] =
		collectReadyCommands(pendingCommands, availableMaterials, at).toSeq

	private case class Induct(task: Task[Transfer, Tray])
	private case class Discharge(task: Task[Transfer, Tray])

	override protected def localInitiateTask(task: Task[Transfer, Tray], at: Long): Unit = {
		assert(task.resource isDefined, "Must have a Tray defined as resource")
		task.resource.!.reserve
		val timeToPickUp =
			physics.estimateElapsedFromNumber(task.resource.!.number,
				geography.location(task.cmd.source))
		Induct(task) ~> self in ((at, timeToPickUp))
	}

	private def completeStaging(from: ActorRef, at: Long): Processing = {
		case Induct(task) =>
			physics.updateLocation(at)
			stageMaterial(task.cmd.uid, task.materials.head._1, task.cmd.source, at)
			val timeToDischarge =
				physics.estimateElapsedFromNumber(task.resource.!.number,
					geography.location(task.cmd.destination))
			log.debug(s"Going to discharge ${task.materials.head} using tray ${task.resource.!.number} with a delay of $timeToDischarge")
			Discharge(task) ~> self in ((at, timeToDischarge))
	}

	private def completeMovement(from: ActorRef, at: Long): Processing = {
		case Discharge(task) =>
			physics.updateLocation(at)
			tryDelivery(task.cmd.uid, task.materials.head._1, task.cmd.destination, at)
	}

	override protected def localFinalizeDelivery(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit = {
		val trayNumber = physics.pointAtIndex(geography.location(via))
		trays(trayNumber.coord.toInt).>>
		completeCommand(cmdId, Seq(load), tick)
	}


	override protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
	}
}
