/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.ddes.Subject
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object MultiProcessorHelper {
	import Processor._
	trait MultiProcessorImplementor[C <: ExecutionCommand, R <: ExecutionResource]
		extends DirectedChannel.Destination[Material]{
		// To be implemented by subclass
		protected def localSelectNextTasks(pendingCommands: List[C],
	                                       availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                                       at: Long): Seq[Task[C, R]]
		protected def localInitiateTask(task: Task[C, R], at: Long): Unit // Same
		protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit // Same
		protected def localFinalizeDelivery(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit

	}

	trait MultiProcessorSupport[C <: ExecutionCommand]
			extends DirectedChannel.Destination[Material]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		def configureOwner(owner: ActorRef): Unit // Same
		def receiveCommand(cmd: C, at: Long): Unit // Same
		def stageMaterial(cmdId: String, material: Material, via: DirectedChannel.End[Material], at: Long): Unit
		def stageMaterial(cmdId: String, material: Material, at: Long): Unit
		def completeCommand(cmdId: String, results: Seq[Material], at: Long):Unit
		def tryDelivery(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit
		def tryDelivery(via: DirectedChannel.Start[Material], tick: Long): Unit // Same
	}

}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait MultiProcessorHelper[C <: Processor.ExecutionCommand, R <: Processor.ExecutionResource]
extends MultiProcessorHelper.MultiProcessorImplementor[C,R]
	with MultiProcessorHelper.MultiProcessorSupport[C] {
	import Processor._

	private var _owner: Option[ActorRef] = None
	def owner: ActorRef = _owner.!

	val maxConcurrency: Int

	override def configureOwner(owner: ActorRef) {
		assert(_owner isEmpty, "Cannot Configure owner twice")
		_owner = owner.?
		registerObserver(owner)
	}

	private val pendingCommands: mutable.ArrayBuffer[C] = mutable.ArrayBuffer[C]()
	protected val currentTasks: mutable.Map[String, Task[C, R]] = mutable.Map.empty

	override def receiveCommand(cmd: C, at: Long): Unit = {
		log.debug(s"Receiving Command: $cmd")
		pendingCommands += cmd
		tryExecution(at)
	}

	private def tryExecution(at: Long): Unit = {
		if (currentTasks.size < maxConcurrency) {
			val nextTasks = localSelectNextTasks(pendingCommands.toList, availableMaterials.available, at)
			while((nextTasks nonEmpty) && (currentTasks.size < maxConcurrency)) {
				val nextTask = nextTasks.head
				currentTasks += nextTask.cmd.uid -> nextTask
				localInitiateTask(nextTask, at)
				notify(StartTask(nextTask.cmd.uid, nextTask.materials.keys.toSeq), at)
				nextTask.materials.keys.foreach { availableMaterials.retire }
				pendingCommands -= nextTask.cmd
			}
		}
	}

	final override def onAccept(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
		notify(ReceiveLoad(via, load), tick)
		availableMaterials.add(load, via)
		localReceiveMaterial(via, load, tick)
		tryExecution(tick)
	}

	override def stageMaterial(cmdId: String, material: Material, via: DirectedChannel.End[Material], at: Long): Unit = {
		via.doneWithLoad(material, at)
		stageMaterial(cmdId, material, at)
	}
	override def stageMaterial(cmdId: String, material: Material, at: Long): Unit =
		notify(StageLoad(currentTasks(cmdId).cmd.uid, material.?), at)

	override def completeCommand(cmdId: String, results: Seq[Material], at: Long):Unit = {
		notify(CompleteTask(cmdId, currentTasks(cmdId).materials.keys.toSeq, results), at)
		currentTasks -= cmdId
		tryExecution(at)
	}

	private val pendingDeliveries: mutable.Map[DirectedChannel.Start[Material], mutable.Queue[(Material, String)]] =
		mutable.Map()

	final override def onRestore(via: DirectedChannel.Start[Material], tick: Long): Unit =
		tryDelivery(via, tick)

	override def tryDelivery(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit = {
		if(! pendingDeliveries.contains(via)) pendingDeliveries += via -> mutable.Queue()
		pendingDeliveries(via).enqueue((load, cmdId))
		tryDelivery(via, tick)
	}

	override def tryDelivery(via: DirectedChannel.Start[Material], tick: Long): Unit = {
		if(pendingDeliveries contains via) {
			var ongoing = true
			while(pendingDeliveries(via).nonEmpty && ongoing) {
				val (load, cmdId) = pendingDeliveries(via).head
				if (via.sendLoad(load, tick)) {
					notify(DeliverResult(cmdId, via, load), tick)
					localFinalizeDelivery(cmdId, load, via, tick)
					pendingDeliveries(via).dequeue
					currentTasks -= cmdId
					tryExecution(tick)
				} else ongoing = false
			}
		}
	}

	private object availableMaterials {
		private val availableMaterials: mutable.Map[Material, DirectedChannel.End[Material]] = mutable.Map.empty

		def available: Map[Material, DirectedChannel.End[Material]] = availableMaterials.toMap

		def retire(load: Material): Option[(Material, DirectedChannel.End[Material])] = {
			if (availableMaterials contains load) {
				val via = availableMaterials(load)
				availableMaterials -= load
				(load, via).?
			} else None
		}

		def peek(load: Material): Option[DirectedChannel.End[Material]] = {
			availableMaterials.get(load)
		}

		private[MultiProcessorHelper] def add(load: Material, via: DirectedChannel.End[Material]): Unit =
			availableMaterials += load -> via

	}
}
