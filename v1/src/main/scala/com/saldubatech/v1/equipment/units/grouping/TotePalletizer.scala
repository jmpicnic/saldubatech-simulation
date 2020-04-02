/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.v1.equipment.units.grouping

import akka.actor.{ActorRef, Props}
import com.saldubatech.v1.base.Material.{DefaultPalletBuilder, Tote, TotePallet}
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.base.processor.Processor.ConfigureOwner
import com.saldubatech.v1.base.processor.{MultiProcessorHelper, Task}
import com.saldubatech.v1.base.resource.KittingSlot
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl}
import com.saldubatech.util.Lang._

import scala.collection.mutable

object TotePalletizer {
		// Specialized Types
	type ToteChannel = DirectedChannel[Tote]
	type PalletChannel = DirectedChannel[TotePallet]
	type ToteChannelEnd = DirectedChannel.End[Tote]
	type PalletChannelStart = DirectedChannel.Start[TotePallet]
	type PalletSlot = KittingSlot[Tote, TotePallet]

	def apply[C <: TotePalletizer.GroupExecutionCommand](name: String,
                                            capacity: Int,
                                            induct: TotePalletizer.ToteChannel,
                                            discharge: TotePalletizer.PalletChannel,
                                            palletBuilder: (String, List[Tote]) => Option[TotePallet] = DefaultPalletBuilder,
                                            perSlotCapacity: Option[Int] = None)
                                           (implicit gw: Gateway) =
		gw.simActorOf(Props(new TotePalletizer[C](name, capacity, induct, discharge, palletBuilder, perSlotCapacity)), name)

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
			case GroupByNumber(quantity, _) => quantity < slot.currentContents.size
			case GroupByIds(ids, _) => ids.contains(mat.uid)
		}

		override protected def canStart(at: Long): Boolean =
			initialMaterials.nonEmpty || slot.currentContents.nonEmpty


		protected def canCompletePreparations(at: Long): Boolean = true

		protected def canComplete(at: Long): Boolean = {
			cmd match {
				case c @ GroupByNumber(q, _) =>	q == slot.currentContents.size
				case c @ GroupByIds(ids, _) => ids.forall(id => slot.currentContents.map(_.uid).contains(id))
			}
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
			induct.end.loadReceiving(from, at) orElse discharge.start.restoringResource(from, at)

	override protected def findResource(cmd: C, resources: mutable.Map[String, PalletSlot]): Option[(C, String, PalletSlot)] =
		resources.collectFirst {
			case (id, slot) if slot.isIdle => (cmd, id, slot)
		}

	override protected def consumeMaterial(via: ToteChannelEnd, load: Tote, tick: Long): Boolean = {
		val candidates = currentTasks.filter {
			case (_, GroupingTask(cmd, _, slot)) => cmd.canAccept(load, slot.currentContents)
		}
		log.debug(s"Found Task to add load: $candidates within $currentTasks")
		if (candidates isEmpty) false // No matching task, should go to the available materials for the future
		else {
			val task = candidates.values.head
			//>>>>>
			stageMaterial(task.cmd.uid, load, via.?, tick) // Already does the loading into the resource
			log.debug(s"Task $task is loaded checking next steps : ${task.isInProcess}//${task.status}")
			if(task.isInitializing) task.start(tick)
			if(task.isInProcess) {
				log.debug(s"Task $task is inProcess attempt to complete preps")
				task.completePreparations(tick)
			}
			if(task.isReadyToComplete) {
				log.debug(s"Task $task is ready to complete, attempt to complete execution")
				produceAndDeliver(task, tick)
			}
			true
		}
	}

	override protected def collectMaterialsForCommand(cmd: C, resource: PalletSlot, available: mutable.Map[Tote, ToteChannelEnd])
	: Map[Tote, ToteChannelEnd] =
		cmd match {
			case c@GroupByNumber(q, _) => available.take(c.quantity).toMap
			case c@GroupByIds(ids, _) => available.filter(e => ids.contains(e._1.uid)).toMap
		}

	override protected def newTask(cmd: C, materials: Map[Tote, ToteChannelEnd], resource: PalletSlot, at: Long): Option[GroupingTask[C]] =
		GroupingTask[C](cmd, materials, resource)(at).?

	override protected def triggerTask(task: GroupingTask[C], at: Long): Unit =  {
		if (task.start(at)) {
			task.initialMaterials.foreach {
				case (mat, via) =>
					stageMaterial(task.cmd.uid, mat, via.?, at)
					task.slot << _
			}
		}
		if (task.completePreparations(at) && task.isReadyToComplete) produceAndDeliver(task, at)
	}

	private def produceAndDeliver(task: GroupingTask[C], at: Long): Unit = {
		val slot = task.resource.!
		val totes = slot.currentContents
		val taskCompleteTask = task.completeTask(at)
		if(taskCompleteTask && slot.build(task.cmd.uid)) {
			val maybePallet = slot.>>
			val maybePalletDefined = maybePallet.isDefined
			if(maybePalletDefined && taskCompleteTask)
				tryDelivery(task.cmd.uid, maybePallet.!, discharge.start, at)
			else
				assert(false, s"Should be able to complete task $task: MaybePallet: $maybePalletDefined, task.completeTask $taskCompleteTask with $totes")
		}
	}

	override protected def loadOnResource(tsk: GroupingTask[C], material: Option[Tote], via: Option[ToteChannelEnd], at: Long)
	: Unit = material.foreach{mat => tsk.resource.! << mat}

	override def offloadFromResource(resource: Option[PalletSlot]): Unit = resource.!.>>

	override protected def finalizeTask(cmdId: String, load: TotePallet, via: PalletChannelStart, tick: Long): Unit =
		completeCommand(cmdId, Seq(load), tick)
}
