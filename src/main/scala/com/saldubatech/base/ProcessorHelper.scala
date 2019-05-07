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
import com.saldubatech.ddes.{SimMessage, Subject}
import com.saldubatech.utils.Boxer._

object ProcessorHelper {
	import Processor._


	trait ProcessorImplementor[C <: ExecutionCommand, R <: ExecutionResource]
		extends DirectedChannel.Destination[Material]{
		// To be implemented by subclass
		protected def localSelectNextExecution(pendingCommands: List[C],
	                                       availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                                       at: Long): Option[Task[C, R]]
		protected def localInitiateTask(task: Task[C, R], at: Long): Unit
		protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit
		protected def localFinalizeDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit

	}

	trait ProcessorSupport[C <: ExecutionCommand]
			extends DirectedChannel.Destination[Material]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		def configureOwner(owner: ActorRef): Unit
		def receiveCommand(cmd: C, at: Long): Unit
		def stageMaterial(material: Material, via: DirectedChannel.End[Material], at: Long): Unit
		def stageMaterial(material: Material, at: Long): Unit
		def completeCommand(results: Seq[Material], at: Long):Unit
		def tryDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit
		def tryDelivery(via: DirectedChannel.Start[Material], tick: Long): Unit
	}

	trait ProcessorHelperI[C <: ExecutionCommand, R <: ExecutionResource]
		extends ProcessorSupport[C]
			with ProcessorImplementor[C,R]
}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait ProcessorHelper[C <: Processor.ExecutionCommand, R <: Processor.ExecutionResource]
	extends MultiProcessorHelper[C, R]
		with ProcessorHelper.ProcessorHelperI[C,R] {
	import Processor.Task

	override val maxConcurrency: Int = 1

	override protected def localSelectNextTasks(pendingCommands: List[C], availableMaterials: Map[Material, DirectedChannel.End[Material]], at: Long): Seq[Task[C, R]] = {
		val t = localSelectNextExecution(pendingCommands, availableMaterials, at)
		if(t isDefined) Seq(t.!) else Seq()
	}

	private def cmdId = {
		currentTasks.head._1
	}
	override def stageMaterial(material: Material, via: DirectedChannel.End[Material], at: Long): Unit = {
		super.stageMaterial(cmdId, material, via, at)
	}
	override def stageMaterial(material: Material, at: Long): Unit =
		super.stageMaterial(cmdId, material, at)


	override def completeCommand(results: Seq[Material], at: Long):Unit =
		completeCommand(cmdId, results, at)

	override def tryDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit =
		super.tryDelivery(cmdId, load, via, tick)

	override protected def localFinalizeDelivery(cmdId: String, load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit =
		localFinalizeDelivery(load, via, tick)

}
