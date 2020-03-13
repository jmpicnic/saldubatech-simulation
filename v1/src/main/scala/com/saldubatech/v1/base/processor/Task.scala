/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.v1.base.processor

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.base.resource.Resource
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.ddes.SimMessage

import scala.collection.mutable

object Task {

	object Status extends Enumeration {
		val Initializing, InProcess, ReadyToComplete, Complete = Value
	}

	trait ExecutionCommand extends SimMessage
	class ExecutionCommandImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends SimMessage.Impl(_id) with ExecutionCommand
}

abstract class Task[C <: Task.ExecutionCommand, M<: Material, PR <: Material, R <: Resource]
	(val cmd: C, val initialMaterials: Map[M, DirectedChannel.End[M]], val resource: Option[R] = None)(implicit createdAt: Long)
		extends Identification.Impl {
	private var _status: Task.Status.Value = Task.Status.Initializing


	def status: Task.Status.Value = _status
	private def inProcess: Task.Status.Value = {
		_status = Task.Status.InProcess
		_status
	}
	private def readyToComplete: Task.Status.Value = {
		_status = Task.Status.ReadyToComplete
		_status
	}
	private def complete: Task.Status.Value = {
		_status = Task.Status.Complete
		_status
	}

	def isInitializing: Boolean = _status == Task.Status.Initializing
	def isInProcess: Boolean = _status == Task.Status.InProcess
	def isReadyToComplete: Boolean = _status == Task.Status.ReadyToComplete
	def isComplete: Boolean = _status == Task.Status.Complete

	def canAccept(mat: M): Boolean = {
		List(Task.Status.Initializing, Task.Status.InProcess).contains(_status) && isAcceptable(mat)
	}
	def isAcceptable(mat: M): Boolean


	def start(at: Long): Boolean =
		if(canStart(at)) {
			inProcess
			true
		} else false
	protected def canStart(at: Long): Boolean

	def completePreparations(at: Long): Boolean =
		if(isReadyToComplete) true
		else if(isInProcess && canCompletePreparations(at)) {
			readyToComplete
			true
		}
		else false

	protected def canCompletePreparations(at: Long): Boolean

	def completeTask(at: Long): Boolean = {
		if (_status == Task.Status.ReadyToComplete && canComplete(at)) {
			complete
			true
		}	else false
	}
	protected def canComplete(at: Long): Boolean

}