/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.grouping

import akka.actor.ActorRef
import com.saldubatech.base.Material.{Tote, TotePallet, TotePalletBuilder}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Processor.ConfigureOwner
import com.saldubatech.base.resource.Use
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
	type PalletSlot = Slot[Tote, TotePallet]


	sealed abstract class GroupExecutionCommand(val slot: Option[String] = None, name:String = java.util.UUID.randomUUID().toString)
		extends Task.ExecutionCommandImpl(name) {
		def canAccept(tote: Tote, currentContents: Int): Boolean
	}

	case class GroupByNumber(quantity: Int, override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
		def canAccept(tote: Tote, currentContents: Int): Boolean = currentContents < quantity

	}
	case class GroupByIds(ids: List[String], override val slot: Option[String] = None) extends GroupExecutionCommand(slot) {
				def canAccept(tote: Tote, currentContents: Int): Boolean = currentContents < ids.length && ids.contains(tote.uid)
	}

	case class GroupingTask
	[C <: GroupExecutionCommand](override val cmd: C,
	                             override val initialMaterials: Map[Tote, ToteChannelEnd],
	                             slot: PalletSlot)(implicit createdAt: Long)
		extends Task[C, Tote, TotePallet, PalletSlot](cmd, Map.empty, Some(slot)) {

		override protected def doProduce(at: Long): Boolean = {
			val p = slot.build
			if(p isEmpty) false
			else {
				_products ++ p
				complete
				true
			}
		}

		override protected def isReadyToStart: Boolean = cmd match {
			case GroupByNumber(quantity, _) => quantity == currentMaterials.size
			case GroupByIds(ids,_) => ids.size == currentMaterials.size
		}

		override protected def doStart(at: Long): Boolean = {
			_materials.foreach {
				case (mat, via) => slot << Slot.Deposit(None, mat)
			}
			true
		}
		override protected def prepareToComplete(at: Long): Boolean = {true}

		override def isAcceptable(mat: Tote): Boolean = cmd match {
			case GroupByNumber(quantity, _) => quantity < currentMaterials.size
			case GroupByIds(ids,_) => ids.contains(mat.uid)
		}

		override def addMaterialPostStart(mat: Tote, via: DirectedChannel.End[Tote], at: Long): Boolean = {
			slot << Slot.Deposit(None, mat)
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

	 protected def localSelectNextTasks(pendingCommands: List[C],
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
			if(t.start(at)) { // But if the task is complete, do not return it, just complete it.
				if(t.completePreparations(at) && t.produce(at)) {
					tryDelivery(cmd.uid, t.slot.build.!, discharge.start, at)
					None
				} else t.?
			} else t.?
		}

	private def findCandidateCommands(commands: List[C]): Map[C, PalletSlot] =
		commands.flatMap {c =>
					val slot = acquire
					if(slot isDefined) Some(c -> slot.!.item) else None
		}.toMap

	protected def localInitiateTask(task: GroupingTask[C], at: Long): Unit = {
		if(task.start(at) && task.completePreparations(at) && task.produce(at))
			tryDelivery(task.cmd.uid, task.products.head, discharge.start, at)
	}

	override protected def localReceiveMaterial(via: ToteChannelEnd, load: Tote, tick: Long): Unit =
		findActiveTask(load).foreach(t => t.addMaterial(load, via, tick))

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

/** As seen from class TotePalletizer, the missing signatures are as follows.
 *  For convenience, these are usable as stub implementations.
 */
  protected def collectMaterials(cmd: C,resource: com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot,available: scala.collection.mutable.Map[com.saldubatech.base.Material.Tote,com.saldubatech.base.channels.DirectedChannel.End[com.saldubatech.base.Material.Tote]]): Map[com.saldubatech.base.Material.Tote,com.saldubatech.base.channels.DirectedChannel.End[com.saldubatech.base.Material.Tote]] = ???
  protected def findResource(cmd: C,resources: scala.collection.mutable.Map[String,com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot]): Option[(C, String, com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot)] = ???
  protected def loadOnResource(resource: Option[com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot],material: Option[com.saldubatech.base.Material.Tote]): Unit = ???
  protected def newTask(cmd: C,materials: Map[com.saldubatech.base.Material.Tote,com.saldubatech.base.channels.DirectedChannel.End[com.saldubatech.base.Material.Tote]],resource: com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot,at: Long): Option[com.saldubatech.equipment.units.grouping.TotePalletizer.GroupingTask[C]] = ???
  protected def offloadFromResource(resource: Option[com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot],product: Set[com.saldubatech.base.Material.TotePallet]): Unit = ???
  protected def resources: Map[String,com.saldubatech.equipment.units.grouping.TotePalletizer.PalletSlot] = ???
  protected def triggerTask(task: com.saldubatech.equipment.units.grouping.TotePalletizer.GroupingTask[C],at: Long): Unit = ???

	override protected def updateState(at: Long): Unit = {}
}
