/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.base.channels.DirectedChannel
	import Processor._
import com.saldubatech.ddes.Subject
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object MultiProcessorHelper {
	trait MultiProcessorImplementor[C <: ExecutionCommand, R <: ExecutionResource,
	M <: Material, PR <: Material, TK <: Task[C, M, R]]
		extends DirectedChannel.Source[PR]{
		// To be implemented by subclass
		protected def localSelectNextTasks(pendingCommands: List[C],
	                                       availableMaterials: Map[M, DirectedChannel.End[M]],
	                                       at: Long): Seq[TK]
		protected def localInitiateTask(task: TK, at: Long): Unit // Same
		protected def localReceiveMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Unit // Same
		protected def localFinalizeDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		protected def updateState(at: Long): Unit
	}

	trait MultiProcessorSupport[C <: ExecutionCommand, M <: Material, PR <: Material]
			extends DirectedChannel.Sink[M]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		def configureOwner(owner: ActorRef): Unit // Same
		def receiveCommand(cmd: C, at: Long): Unit // Same
		def stageMaterial(cmdId: String, material: M, via: DirectedChannel.End[M], at: Long): Unit
		def stageMaterial(cmdId: String, material: M, at: Long): Unit
		def completeCommand(cmdId: String, results: Seq[PR], at: Long):Unit
		def tryDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit // Same
	}

}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait MultiProcessorHelper[C <: ExecutionCommand, R <: ExecutionResource,
	M <: Material, PR <: Material, TK <: Task[C, M, R]]
extends MultiProcessorHelper.MultiProcessorImplementor[C, R, M, PR, TK]
	with MultiProcessorHelper.MultiProcessorSupport[C, M, PR] {
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
	protected val currentTasks: mutable.Map[String, TK] = mutable.Map.empty

	override def receiveCommand(cmd: C, at: Long): Unit = {
		log.debug(s"Multi-Processor Receiving Command: $cmd")
		updateState(at)
		pendingCommands += cmd
		tryExecution(at)
	}

	private def tryExecution(at: Long): Unit = {
		if (currentTasks.size < maxConcurrency) {
			val nextTasks: Seq[TK] = localSelectNextTasks(pendingCommands.toList, availableMaterials.available, at)
			nextTasks.take(maxConcurrency - currentTasks.size) foreach { nextTask =>
				currentTasks += nextTask.cmd.uid -> nextTask
				localInitiateTask(nextTask, at)
				notify(StartTask(nextTask.cmd.uid, nextTask.materials.keys.toSeq), at)
				nextTask.materials.keys.foreach { availableMaterials.retire }
				pendingCommands -= nextTask.cmd
			}
		}
	}

	final override def receiveMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Unit = {
		updateState(tick)
		notify(ReceiveLoad(via, load), tick)
		availableMaterials.add(load, via)
		localReceiveMaterial(via, load, tick)
		tryExecution(tick)
	}

	override def stageMaterial(cmdId: String, material: M, via: DirectedChannel.End[M], at: Long): Unit = {
		via.doneWithLoad(material, at)
		stageMaterial(cmdId, material, at)
	}
	override def stageMaterial(cmdId: String, material: M, at: Long): Unit =
		notify(StageLoad(currentTasks(cmdId).cmd.uid, material.?), at)

	override def completeCommand(cmdId: String, results: Seq[PR], at: Long):Unit = {
		notify(CompleteTask(cmdId, currentTasks(cmdId).materials.keys.toSeq, results), at)
		currentTasks -= cmdId
		tryExecution(at)
	}

	private val pendingDeliveries: mutable.Map[DirectedChannel.Start[PR], mutable.Queue[(PR, String)]] =
		mutable.Map()

	final override def restoreChannelCapacity(via: DirectedChannel.Start[PR], tick: Long): Unit = {
		updateState(tick)
		tryDelivery(via, tick)
	}

	override def tryDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit = {
		if(! pendingDeliveries.contains(via)) pendingDeliveries += via -> mutable.Queue()
		pendingDeliveries(via).enqueue((load, cmdId))
		tryDelivery(via, tick)
	}

	override def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit = {
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
		private val availableMaterials: mutable.Map[M, DirectedChannel.End[M]] = mutable.Map.empty

		def available: Map[M, DirectedChannel.End[M]] = availableMaterials.toMap

		def retire(load: M): Option[(M, DirectedChannel.End[M])] = {
			if (availableMaterials contains load) {
				val via = availableMaterials(load)
				availableMaterials -= load
				(load, via).?
			} else None
		}

		def peek(load: M): Option[DirectedChannel.End[M]] = {
			availableMaterials.get(load)
		}

		private[MultiProcessorHelper] def add(load: M, via: DirectedChannel.End[M]): Unit =
			availableMaterials += load -> via
	}
}
