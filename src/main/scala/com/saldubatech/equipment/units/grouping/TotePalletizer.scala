/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.grouping

import akka.actor.ActorRef
import com.saldubatech.base.Material.{Tote, TotePallet, TotePalletBuilder}
import com.saldubatech.base.Processor.{ConfigureOwner, ExecutionResource, ExecutionResourceImpl, StageLoad, Task}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.resource.Use
import com.saldubatech.base.{MultiProcessorHelper, Processor}
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
	type PalletSlot = Slot[Tote, TotePallet]


	sealed abstract class GroupExecutionCommand(val slot: Option[String] = None, name:String = java.util.UUID.randomUUID().toString)
		extends Processor.ExecutionCommandImpl(name) {
		def canAccept(tote: Tote, currentContents: Int): Boolean
	}

	case class GroupByNumber(quantity: Int, override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
		def canAccept(tote: Tote, currentContents: Int): Boolean = currentContents < quantity

	}
	case class GroupByIds(ids: List[String], override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
				def canAccept(tote: Tote, currentContents: Int): Boolean = currentContents < ids.length && ids.contains(tote.uid)
	}

	case class GroupingTask
	[C <: GroupExecutionCommand](groupCommand: C,
	                             initialMaterials: Map[Tote, ToteChannelEnd],
	                             slot: PalletSlot)(implicit createdAt: Long)
		extends Task[C, Tote, PalletSlot](groupCommand, Map.empty, Some(slot)) {
		def isComplete: Boolean = cmd match {
			case c: GroupByNumber => c.quantity == resource.!.slot.quantity
			case c: GroupByIds => c.ids.length == resource.!.slot.quantity
		}
	}
}

class TotePalletizer[C <: TotePalletizer.GroupExecutionCommand](name: String,
                     capacity: Int,
                     induct: TotePalletizer.ToteChannel,
                     discharge: TotePalletizer.PalletChannel,
                     perSlotCapacity: Option[Int] = None)(implicit gw: Gateway)
	extends SimActorImpl(name, gw)
		with MultiProcessorHelper[C, TotePalletizer.PalletSlot, Tote, TotePallet, TotePalletizer.GroupingTask[C]]
		with Use.DiscreteUsable[TotePalletizer.PalletSlot] {
	import TotePalletizer._

	implicit val palletBuilder: TotePalletBuilder = new TotePalletBuilder(capacity.?)
	lazy val maxConcurrency: Int = capacity
	lazy val items: Map[String, PalletSlot] =
		(0 until capacity).toList.map(idx => f"$name$idx%03d_Slot" -> Slot(f"$name$idx%03d_Slot", perSlotCapacity)).toMap

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			induct.registerEnd(this)
			discharge.registerStart(this)
	}

	override def process(from: ActorRef, at: Long): Processing =
		commandReceiver(from, at) orElse
			//completeStaging(from, at) orElse
			induct.end.loadReceiving(from, at) orElse
			discharge.start.restoringResource(from, at)

	private def commandReceiver(from: ActorRef, at: Long): Processing = {
		case cmd : C => receiveCommand(cmd, at)
	}

	override protected def localSelectNextTasks(pendingCommands: List[C],
	                                            availableMaterials: Map[Tote, ToteChannelEnd],
	                                            at: Long): Seq[GroupingTask[C]] = {
		val candidatesWithResources: Map[C, PalletSlot] = findCandidateCommands(pendingCommands)
		collectMaterialsIntoTasks(candidatesWithResources, availableMaterials)(at).toSeq
	}

	private def collectMaterialsIntoTasks(candidates: Map[C, PalletSlot],
	                                      availableMaterials: Map[Tote, ToteChannelEnd])
	                                     (implicit at: Long): Iterable[GroupingTask[C]] =
		availableMaterials.flatMap { // For each material, find the first command that is applicable
			case (tote, via) =>
				candidates.collectFirst {
					case (cmd, slot) if cmd.canAccept(tote, slot.usage) => (cmd, tote, via)
				}
		}.groupBy(e => e._1) // Group by the command
  	.flatMap{ case (cmd, contents) => // Generate the Tasks for each group
			val t = new GroupingTask[C](cmd, contents.map(e => e._2 -> e._3).toMap, candidates(cmd))(at)
			contents.foreach {case (_, tote, _) => t.slot << Slot.Deposit(None, tote)}
			if(t.isComplete) { // But if the task is complete, do not return it, just complete it.
				contents.foreach {case (_, tote, via) => stageMaterial(cmd.uid, tote, via, at)}
				tryDelivery(cmd.uid, t.slot.build.!, discharge.start, at)
				None
			} else t.?
		}

	private def findCandidateCommands(commands: List[C]): Map[C, PalletSlot] =
		commands.flatMap {c =>
					val slot = acquire
					if(slot isDefined) Some(c -> slot.!.item) else None
		}.toMap

	override protected def localInitiateTask(task: GroupingTask[C], at: Long): Unit = {
		val slot = task.resource.head
		slot.slot.currentContents.foreach{tote =>
			stageMaterial(task.cmd.uid, tote, task.materials(tote), at)}
		if(task.isComplete)
			tryDelivery(task.cmd.uid, slot.build.!, discharge.start, at)
	}

	override protected def localReceiveMaterial(via: ToteChannelEnd, load: Tote, tick: Long): Unit =
		findActiveTask(load).foreach {
			t =>
				t.resource.head << Slot.Deposit(None, load)
				stageMaterial(t.cmd.uid, load, via, tick)
		}

	private def findActiveTask(tote: Tote): Option[GroupingTask[C]] = {
		val tsk = currentTasks.filter{
			case (_, GroupingTask(cmd, _, slot)) => cmd.canAccept(tote, slot.usage)
		}
		if(tsk nonEmpty) tsk.head._2.?
		else None
	}

	override protected def localFinalizeDelivery(cmdId: String, load: TotePallet, via: PalletChannelStart, tick: Long): Unit = {
		val tsk = currentTasks(cmdId)
		release(Use.Usage(None, tsk.slot))
		completeCommand(cmdId,Seq(load),tick)
	}

	override protected def updateState(at: Long): Unit = {}
}
