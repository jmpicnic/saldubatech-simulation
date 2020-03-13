/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import com.saldubatech.base.Material
import com.saldubatech.utils.Boxer._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object ControlledProcessor {
	class JobCommand(val loadId: String) extends ProcessingCommand.Impl()
}

trait ControlledProcessor
    extends EquipmentActorMixIn
      with ExecutionProxy.ExecutionObserver {
	import ControlledProcessor._


	private val newLoads = mutable.Map[String, Material]()
	private val readyJobs = ListBuffer[PhysicalJob[JobCommand]]()

	/* These three are to be overriden for more sophisticated Command management */
	private val pendingCommands = mutable.Map[String, ProcessingCommand]()
	protected def findCommand(loadId: String): Option[ProcessingCommand] = {
		pendingCommands.get(loadId)
	}

	protected def consumeCommand(loadId: String): Unit = {
		pendingCommands -= loadId
	}

	protected def enqueueCommand(command: JobCommand): Unit = {
		pendingCommands += command.loadId -> command
	}

	protected def receiveProcessingCommand(command: JobCommand, at: Long): Unit = {
		if(newLoads contains command.loadId) {
			readyJobs += PhysicalJob[JobCommand](newLoads(command.loadId).?, command)
			newLoads -= command.loadId
		} else enqueueCommand(command)
		attemptProcessing(at)
	}

/*	override def newJobArrival(load: Material, at: Long): Unit = {
		val cmd = findCommand(load.uid)
		if(cmd isDefined) {
			readyJobs += PhysicalJob(load.?, cmd.!)
			consumeCommand(load.uid)
		} else newLoads += load.uid -> load
		attemptProcessing(at)
	}*/

	def attemptProcessing(at: Long): Unit = {
		var active = true
		while(active) {
			val job = selectJob(readyJobs, at)
			if (job.isDefined && attemptJob(job.!, at)) consumeJob(job.!)
			else active = false
		}
	}

	def selectJob(readyJobs: ListBuffer[PhysicalJob[JobCommand]], at: Long): Option[PhysicalJob[JobCommand]] = {
		readyJobs.headOption
	}
	def consumeJob(job: PhysicalJob[JobCommand]): Unit = {
		readyJobs -= job
	}

	def attemptJob(job: PhysicalJob[JobCommand], at:Long): Boolean
}
