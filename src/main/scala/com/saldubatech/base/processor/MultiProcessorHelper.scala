/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.processor

import akka.actor.ActorRef
import com.saldubatech.base.{Material, Owned}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Task._
import com.saldubatech.base.processor.Processor.ExecutionNotification
import com.saldubatech.base.resource.{ResourcePool, Use}
import com.saldubatech.ddes.{Gateway, Subject}
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object MultiProcessorHelper {
	trait MultiProcessorImplementor[C <: ExecutionCommand, R <: ExecutionResource,
	M <: Material, PR <: Material, TK <: Task[C, M, PR, R]]
		extends DirectedChannel.Source[PR]{
		// To be implemented by subclass
		protected def resources: Map[String, R]
		protected def triggerTask(task: TK, at: Long): Unit // Same
		protected def localReceiveMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Unit // Same
		protected def localFinalizeDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		protected def updateState(at: Long): Unit
		protected def newTask(cmd: C, materials: Map[M, DirectedChannel.End[M]], resource: R, at: Long): Option[TK]
		protected def findResource(cmd: C, resources: mutable.Map[String, R]): Option[(C, String, R)]
		protected def collectMaterials(cmd: C, resource: R, available: mutable.Map[M, DirectedChannel.End[M]]): Map[M, DirectedChannel.End[M]]
		protected def loadOnResource(resource: Option[R], material: Option[M])
		protected def offloadFromResource(resource: Option[R], product: Set[PR])
	}

	trait MultiProcessorSupport[C <: ExecutionCommand, M <: Material, PR <: Material]
			extends DirectedChannel.Sink[M]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		protected def configureOwner(owner: ActorRef): Unit // Same
		def receiveCommand(cmd: C, at: Long): Unit // Same
		def stageMaterial(cmdId: String, material: M, via: Option[DirectedChannel.End[M]], at: Long): Unit
		def completeCommand(cmdId: String, results: Seq[PR], at: Long):Unit
		def tryDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit // Same
	}

	trait FcfsResourceSelection[C <: ExecutionCommand, R <: ExecutionResource] {
		protected def findResource(cmd: C, resources: mutable.Map[String, R]): Option[(C, String,  R)] =
			resources.collectFirst{case (id, r) if isAcceptable(cmd, r) => (cmd, id, r)}

		protected def isAcceptable(cmd: C, r: R): Boolean
	}

	trait FcfsMaterialSelection[C <: ExecutionCommand, R <: ExecutionResource, M <: Material] {
		protected def collectMaterials(cmd: C, resource: R,
	                               available: mutable.Map[M, DirectedChannel.End[M]]): Map[M, DirectedChannel.End[M]] =
			available.map{case (m, v) if isAcceptable(cmd, resource, m) => m -> v}.toMap

		protected def isAcceptable(cmd: C, r: R, m: M): Boolean
	}

}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait MultiProcessorHelper
[C <: ExecutionCommand, R <: ExecutionResource, M <: Material, PR <: Material, TK <: Task[C, M, PR, R]]
extends MultiProcessorHelper.MultiProcessorImplementor[C, R, M, PR, TK]
	with MultiProcessorHelper.MultiProcessorSupport[C, M, PR]
	with Owned[Processor.ExecutionNotification] {
	import com.saldubatech.base.processor.Processor._

	override def receiveCommand(cmd: C, at: Long): Unit = {
		log.info(s"Multi-Processor Receiving Command: $cmd")
		updateState(at)
		pendingCommands += cmd
		tryExecution(at)
	}

	final override def receiveMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Unit = {
		log.debug(s"Receiving Load: $load")
		updateState(tick)
		notify(ReceiveLoad(via, load), tick)
		availableMaterials.add(load, via)
		localReceiveMaterial(via, load, tick)
		tryExecution(tick)
	}

	override def stageMaterial(cmdId: String, material: M, via: Option[DirectedChannel.End[M]], at: Long): Unit = {
		if(via isDefined) via.!.doneWithLoad(material, at)
		stageMaterial(cmdId, material, at)
	}
	// Should this be private?
	private def stageMaterial(cmdId: String, material: M, at: Long): Unit = {
		val tsk = currentTasks(cmdId)
		log.debug(s"Stage Load for task $tsk, that has resource: ${tsk.resource}")
		loadOnResource(tsk.resource, material.?)
		notify(StageLoad(tsk.cmd.uid, material.?), at)
	}

	private def tryExecution(at: Long): Unit = {
		if(!resourcePool.isBusy) { // First general check
			val localResourcePool: mutable.Map[String, R] = mutable.Map.empty ++ resourcePool.availableResources
			val candidateCommands: Map[C, R] = findCandidates(pendingCommands.toList, localResourcePool, at) // Should be returned based on priority from the business side

			val localAvailableMaterials: mutable.Map[M, DirectedChannel.End[M]] = mutable.Map.empty ++ availableMaterials.available
			val candidateTasks = candidateCommands.flatMap {
				case (cmd, res) =>
					val tsk = newTask(cmd, collectMaterials(cmd, res, localAvailableMaterials), res, at)
					tsk.foreach(tk =>	localAvailableMaterials --= tk.initialMaterials.keys)
					tsk
			}
			candidateTasks.foreach{
				case tsk =>
					currentTasks += tsk.cmd.uid -> tsk
					triggerTask(tsk, at)
					notify(StartTask(tsk.cmd.uid, tsk.initialMaterials.keys.toSeq), at)
					pendingCommands -= tsk.cmd
					tsk.resource.foreach(r => resourcePool.acquire(r.uid.?))
					tsk.initialMaterials.foreach{case (m, v) => availableMaterials retire m}
			}
		}
	}
	protected def findCandidates(commands: List[C], resources: mutable.Map[String, R], at: Long): Map[C,R] =
		commands.flatMap(cmd => findResource(cmd, resources)).map{a => resources -= a._2; a._1 -> a._3}.toMap

	override def completeCommand(cmdId: String, results: Seq[PR], at: Long):Unit = {
		val tsk = currentTasks(cmdId)
		log.debug(s"Complete Task: $tsk")
		offloadFromResource(tsk.resource, tsk.products)
		notify(CompleteTask(cmdId, currentTasks(cmdId).initialMaterials.keys.toSeq, results), at)
		resourcePool.release(Use.Usage(None, tsk.resource.!))
		currentTasks -= cmdId
		tryExecution(at)
	}

	private val pendingDeliveries: mutable.Map[DirectedChannel.Start[PR], mutable.Queue[(PR, String)]] =
		mutable.Map()

	override def tryDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit = {
		if(! pendingDeliveries.contains(via)) pendingDeliveries += via -> mutable.Queue()
		pendingDeliveries(via).enqueue((load, cmdId))
		tryDelivery(via, tick)
	}

	final override def restoreChannelCapacity(via: DirectedChannel.Start[PR], tick: Long): Unit = {
		updateState(tick)
		tryDelivery(via, tick)
	}

	override def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit = {
		if(pendingDeliveries contains via) {
			var ongoing = true
			while(pendingDeliveries(via).nonEmpty && ongoing) {
				val (load, cmdId) = pendingDeliveries(via).head
				if (via.sendLoad(load, tick)) {
					log.info(s"Sending result of task ${currentTasks(cmdId).uid} for tote $load")
					notify(DeliverResult(cmdId, via, load), tick)
					localFinalizeDelivery(cmdId, load, via, tick)
					pendingDeliveries(via).dequeue
					currentTasks -= cmdId
				} else {
					log.error(s"Failed to deliver $load to $via")
					ongoing = false
				}
			}
		}
		tryExecution(tick)
	}

	private val pendingCommands: mutable.ArrayBuffer[C] = mutable.ArrayBuffer[C]()
	protected val currentTasks: mutable.Map[String, TK] = mutable.Map.empty
	private val resourcePool = ResourcePool[R](resources)

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
