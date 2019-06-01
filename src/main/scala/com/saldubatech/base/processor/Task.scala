/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.processor

import com.saldubatech.base.{Identification, Material}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.resource.Use.Usable
import com.saldubatech.ddes.SimMessage
import com.saldubatech.equipment.elements.SimpleRandomExecution.CompleteProcessing

import scala.collection.mutable

object Task {

	object Status extends Enumeration {
		val Initializing, ReadyToStart, InProcess, ReadyToComplete, Complete = Value
	}
	trait ExecutionResource extends Usable with Identification

	abstract class ExecutionResourceImpl(_id: String = java.util.UUID.randomUUID().toString) extends
		Identification.Impl(_id) with ExecutionResource
	trait ExecutionCommand extends SimMessage
	class ExecutionCommandImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends SimMessage.Impl(_id) with ExecutionCommand
}

abstract class Task[C <: Task.ExecutionCommand, M<: Material, PR <: Material, R <: Task.ExecutionResource]
	(val cmd: C, val initialMaterials: Map[M, DirectedChannel.End[M]], val resource: Option[R] = None)(implicit createdAt: Long)
		extends Identification.Impl {
	protected val _materials: mutable.Map[M, DirectedChannel.End[M]] = mutable.Map.empty ++ initialMaterials
	protected val _products: mutable.Set[PR] = mutable.Set.empty
	private var _status: Task.Status.Value = if(isReadyToStart) Task.Status.ReadyToStart else Task.Status.Initializing
	def products: Set[PR] = _products.toSet
	def currentMaterials: Map[M, DirectedChannel.End[M]] = _materials.toMap


	def status: Task.Status.Value = _status
	private def readyToStart: Task.Status.Value = {
		_status = Task.Status.ReadyToStart
		_status
	}
	protected def inProcess: Task.Status.Value = {
		_status = Task.Status.InProcess
		_status
	}
	protected def readyToComplete: Task.Status.Value = {
		_status = Task.Status.ReadyToComplete
		_status
	}
	protected def complete: Task.Status.Value = {
		_status = Task.Status.Complete
		_status
	}

	def canAccept(mat: M): Boolean = {
		isAcceptable(mat: M) && List(Task.Status.Initializing, Task.Status.ReadyToStart, Task.Status.InProcess).contains(_status)
	}
	def isAcceptable(mat: M): Boolean

	def addMaterial(mat: M, via: DirectedChannel.End[M], at: Long): Boolean =
		if(canAccept(mat)) {
			_status match {
				case Task.Status.Initializing => _materials += mat -> via; if (isReadyToStart) readyToStart; true
				case Task.Status.ReadyToStart => _materials += mat -> via; true
				case Task.Status.InProcess => _materials += mat -> via; addMaterialPostStart(mat, via, at); via.doneWithLoad(mat, at); true
				case _ => false
			}
		}	else false

	def addMaterialPostStart(mat: M, via: DirectedChannel.End[M], at: Long): Boolean

	protected def stageMaterials(at: Long): Boolean =
		if(status == Task.Status.ReadyToStart) {
			_materials.foreach{case (m, via) => via.doneWithLoad(m, at)}
			true
		} else false
	protected def isReadyToStart: Boolean

	def start(at: Long): Boolean =
		if(_status == Task.Status.ReadyToStart && stageMaterials(at) && doStart(at)) {
			inProcess
			true
		} else false
	protected def doStart(at: Long): Boolean

	def completePreparations(at: Long): Boolean =
		if(_status == Task.Status.InProcess && prepareToComplete(at)) {readyToComplete; true}
		else false
	protected def prepareToComplete(at: Long): Boolean

	def produce(at: Long): Boolean =
		if(_status == Task.Status.ReadyToComplete && doProduce(at)) {complete; true}
		else false
	protected def doProduce(at: Long): Boolean

}