/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.equipment.units.grouping

import akka.actor.ActorRef
import com.saldubatech.base.Material.{Tote, TotePallet, DefaultPalletBuilder}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Processor.ConfigureOwner
import com.saldubatech.base.resource.{KittingSlot, Use}
import com.saldubatech.base.processor.{MultiProcessorHelper, Processor, Task}
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.utils.Boxer._
import com.saldubatech.ddes.SimDSL._

import scala.collection.mutable

object TotePalletizer {
		// Specialized Types
	type ToteChannel = DirectedChannel[Tote]
	type PalletChannel = DirectedChannel[TotePallet]
	type ToteChannelEnd = DirectedChannel.End[Tote]
	type PalletChannelStart = DirectedChannel.Start[TotePallet]
	type PalletSlot = KittingSlot[Tote, TotePallet]


	sealed abstract class GroupExecutionCommand(val slot: Option[String] = None, name:String = java.util.UUID.randomUUID().toString)
		extends Task.ExecutionCommandImpl(name) {
		def canAccept(tote: Tote, currentContents: List[Tote]): Boolean
	}

	case class GroupByNumber(quantity: Int, override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
		def canAccept(tote: Tote, currentContents: List[Tote]): Boolean = currentContents.length < quantity
	}
	case class GroupByIds(ids: List[String], override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
				def canAccept(tote: Tote, currentContents: List[Tote]): Boolean = currentContents.length < ids.length && ids.contains(tote.uid)
	}

	case class GroupingTask
	[C <: GroupExecutionCommand](override val cmd: C,
	                             override val initialMaterials: Map[Tote, ToteChannelEnd],
	                             slot: PalletSlot)(implicit createdAt: Long)
		extends Task[C, Tote, TotePallet, PalletSlot](cmd, initialMaterials, Some(slot)) {

		override def isAcceptable(mat: Tote): Boolean = cmd match {
			case GroupByNumber(quantity, _) => quantity < currentMaterials.size
			case GroupByIds(ids, _) => ids.contains(mat.uid)
		}

		override def addMaterialPostStart(mat: Tote, via: DirectedChannel.End[Tote], at: Long): Boolean =
			slot << mat

		override protected def isReadyToStart: Boolean = cmd match {
			case GroupByNumber(quantity, _) => quantity == currentMaterials.size
			case GroupByIds(ids, _) => ids.size == currentMaterials.size
		}

		override protected def doStart(at: Long): Boolean = {
			for ((mat, via) <- _materials) slot << mat
			true
		}

		protected def prepareToComplete(at: Long): Boolean = {
			true
		}

		protected def doProduce(at: Long): Boolean = {
			val p = slot.build(uid)
			if (p) {
				slot.currentProduct.foreach(_products += _)
				complete
				true
			} else false
		}
	}
}

class TotePalletizer
[C <: TotePalletizer.GroupExecutionCommand](name: String,
                                            capacity: Int,
                                            induct: TotePalletizer.ToteChannel,
                                            discharge: TotePalletizer.PalletChannel,
                                            palletBuilder: (String, List[Tote]) => Option[TotePallet] = DefaultPalletBuilder,
                                            perSlotCapacity: Option[Int] = None)
                                           (implicit gw: Gateway)
	extends SimActorImpl(name, gw)
		with MultiProcessorHelper[C, TotePalletizer.PalletSlot, Tote, TotePallet, TotePalletizer.GroupingTask[C]] {

	import TotePalletizer._

	lazy val maxConcurrency: Int = capacity

	lazy val palletSlots: Map[String, PalletSlot] =
		(0 until capacity).toList.map(idx => f"$name$idx%03d_Slot" -> KittingSlot(perSlotCapacity, palletBuilder, f"$name$idx%03d_Slot")).toMap

	override protected def resources: Map[String, PalletSlot] = palletSlots


	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			induct.registerEnd(this)
			discharge.registerStart(this)
	}

	override protected def updateState(at: Long): Unit = {} // Nothing to do

	private def commandReceiver(from: ActorRef, at: Long): Processing = {
		case cmd: C => receiveCommand(cmd, at)
	}

	override def process(from: ActorRef, at: Long): Processing =
		commandReceiver(from, at) orElse
			//completeStaging(from, at) orElse
			induct.end.loadReceiving(from, at) orElse discharge.start.restoringResource(from, at)

	override protected def findResource(cmd: C, resources: mutable.Map[String, PalletSlot]): Option[(C, String, PalletSlot)] =
		resources.collectFirst {
			case (id, slot) if slot.isIdle => (cmd, id, slot)
		}

	override protected def localReceiveMaterial(via: ToteChannelEnd, load: Tote, tick: Long): Boolean = {
		val task = currentTasks.filter {
			case (_, GroupingTask(cmd, _, slot)) => cmd.canAccept(load, slot.currentContents)
		}
		if (task isEmpty) true // No matching task, should go to the available materials for the future
		else {
			task.values.foreach(_.addMaterial(load, via, tick))
			false
		}
	}

	override protected def collectMaterials(cmd: C, resource: PalletSlot, available: mutable.Map[Tote, ToteChannelEnd])
	: Map[Tote, ToteChannelEnd] =
		cmd match {
			case c@GroupByNumber(q, _) => available.take(c.quantity).toMap
			case c@GroupByIds(ids, _) => available.filter(e => ids.contains(e._1.uid)).toMap
		}

	override protected def newTask(cmd: C, materials: Map[Tote, ToteChannelEnd], resource: PalletSlot, at: Long): Option[GroupingTask[C]] =
		GroupingTask[C](cmd, materials, resource)(at).?

	override protected def triggerTask(task: GroupingTask[C], at: Long): Unit =
		if (task.start(at) && task.completePreparations(at) && task.produce(at))
			tryDelivery(task.cmd.uid, task.products.head, discharge.start, at)


	override protected def loadOnResource(resource: Option[PalletSlot], material: Option[Tote]): Unit =
		material.foreach(resource.!.<< _)

	override def offloadFromResource(resource: Option[PalletSlot], product: Set[TotePallet]): Unit = {
		resource.! >>
	}

	override protected def localFinalizeDelivery(cmdId: String, load: TotePallet, via: PalletChannelStart, tick: Long): Unit =
		completeCommand(cmdId, Seq(load), tick)
}
