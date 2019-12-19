/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.base.processor

import akka.actor.ActorRef
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Task.ExecutionCommand
import com.saldubatech.base.Material
import com.saldubatech.base.resource.Resource
import com.saldubatech.ddes.Subject
import com.saldubatech.util.Lang._

import scala.collection.mutable

object ProcessorHelper {
	import com.saldubatech.base.processor.Processor._

	trait ProcessorImplementor[C <: ExecutionCommand, R <: Resource, M <: Material,
	PR <: Material, TK <: Task[C, M, PR, R]]{
		// To be implemented by subclass
		protected def resource: R

		protected def triggerTask(task: TK, at: Long): Unit
		protected def consumeMaterial(via: DirectedChannel.End[M], load: M, tick: Long): Boolean
		protected def finalizeTask(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
		protected def updateState(at: Long): Unit
		protected def newTask(cmd: C, materials: Map[M, DirectedChannel.End[M]], resource: R, at: Long): Option[TK]
		protected def collectMaterialsForCommand(cmd: C, resource: R, available: mutable.Map[M, DirectedChannel.End[M]]): Map[M, DirectedChannel.End[M]]
		protected def loadOnResource(task: TK, material: Option[M], via: Option[DirectedChannel.End[M]], at: Long)
		protected def offloadFromResource(resource: Option[R])

	}

	trait ProcessorSupport[C <: ExecutionCommand, M <: Material, PR <: Material]
			extends DirectedChannel.Source[PR]
			with DirectedChannel.Sink[M]
			with Subject[ExecutionNotification] {
		// To be called from subclass
		protected def configureOwner(owner: ActorRef): Unit

		def receiveCommand(cmd: C, at: Long): Unit // Same
		def tryDelivery(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit

		def stageMaterial(material: M, via: Option[DirectedChannel.End[M]], at: Long): Unit
		def completeCommand(results: Seq[PR], at: Long):Unit
		def tryDelivery(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit
//		def tryDelivery(via: DirectedChannel.Start[PR], tick: Long): Unit
	}
}

/**
	* A super class to handle all the material and command pairing in a processor
	*/
trait ProcessorHelper[C <: ExecutionCommand, R <: Resource,
	M <: Material, PR <: Material, TK <: Task[C, M, PR, R]]
	extends MultiProcessorHelper[C, R, M, PR, TK]
		with ProcessorHelper.ProcessorImplementor[C,R, M, PR, TK]
		with ProcessorHelper.ProcessorSupport[C,M,PR]{

	override protected def resources: Map[String, R] = Map(resource.uid -> resource)

	override protected def findResource(cmd: C, resources: mutable.Map[String, R]): Option[(C, String, R)] =
		(cmd, resource.uid, resource).?

	private def cmdId = currentTasks.head._1

	override def stageMaterial(material: M, via: Option[DirectedChannel.End[M]], at: Long): Unit =
		super.stageMaterial(cmdId, material, via, at)


	override def completeCommand(results: Seq[PR], at: Long):Unit =
		super.completeCommand(cmdId, results, at)

	override def tryDelivery(load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit =
		super.tryDelivery(cmdId, load, via, tick)

	override def finalizeTask(cmdId: String, load: PR, via: DirectedChannel.Start[PR], tick: Long): Unit =
		finalizeTask(load,via,tick)

}
