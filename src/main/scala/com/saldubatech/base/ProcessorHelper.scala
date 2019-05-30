/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.base.Processor.{ExecutionCommand, ExecutionResource, Task}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.Subject
import com.saldubatech.utils.Boxer._

object ProcessorHelper {
	import Processor._

	trait ProcessorImplementor[C <: ExecutionCommand, R <: ExecutionResource, M <: Material, PR <: Material, TK <: Task[C, M, R]]
		extends DirectedChannel.Destination[M]{
		// To be implemented by subclass
		protected def localSelectNextExecution(pendingCommands: List[C],
	                                       availableMaterials: Map[M, DirectedChannel.End[M]],
	                                       at: Long): Option[TK]
		protected def localInitiateTask(task: TK, at: Long): Unit
		protected def localReceiveMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Unit
		protected def localFinalizeDelivery(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
	}

	trait ProcessorSupport[C <: ExecutionCommand, M <: Material, PR <: Material]
			extends DirectedChannel.Source[PR]
			with DirectedChannel.Sink[M]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		def configureOwner(owner: ActorRef): Unit
		def receiveCommand(cmd: C, at: Long): Unit
		def stageMaterial(material: M, via: DirectedChannel.End[M], at: Long): Unit
		def stageMaterial(material: M, at: Long): Unit
		def completeCommand(results: Seq[PR], at: Long):Unit
		def tryDelivery(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit
	}

	trait ProcessorHelperI[C <: ExecutionCommand, R <: ExecutionResource, M <: Material, PR <: Material, TK <: Task[C, M, R]]
		extends ProcessorSupport[C, M, PR]
			with ProcessorImplementor[C,R, M, PR, TK]
}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait ProcessorHelper[C <: ExecutionCommand, R <: ExecutionResource,
	M <: Material, PR <: Material, TK <: Task[C, M, R]]
	extends MultiProcessorHelper[C, R, M, PR, TK] with ProcessorHelper.ProcessorHelperI[C,R, M, PR, TK] {
	import Processor.Task

	final override val maxConcurrency: Int = 1

	final override protected def localSelectNextTasks(pendingCommands: List[C], availableMaterials: Map[M, DirectedChannel.End[M]], at: Long): Seq[TK] =
		localSelectNextExecution(pendingCommands, availableMaterials, at).toSeq

	private def cmdId = currentTasks.head._1

	override def stageMaterial(material: M, via: DirectedChannel.End[M], at: Long): Unit = {
		super.stageMaterial(cmdId, material, via, at)
	}
	override def stageMaterial(material: M, at: Long): Unit =
		super.stageMaterial(cmdId, material, at)

	override def completeCommand(results: Seq[PR], at: Long):Unit =
		completeCommand(cmdId, results, at)

	override def tryDelivery(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit =
		super.tryDelivery(cmdId, load, via, tick)

	override protected def localFinalizeDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit =
		localFinalizeDelivery(load, via, tick)
}
