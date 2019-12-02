/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.spec

import com.saldubatech.base.{Identification, Material}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.util.Implicits._

object ProcessorProtocol {
	// This may move to somewhere more "basic"

	case class Job(id: String = java.util.UUID.randomUUID) extends Identification.Impl

	case class Task(id: String = java.util.UUID.randomUUID,
	                job: Job,
	                initialMaterials: Map[Material, DirectedChannel.Source[Material]],
	                targetChannel: DirectedChannel.Sink[Material]) extends Identification.Impl

	// Implemented by Processor
	trait Processor {
		def submittTask(task: Task): Unit
		def addMaterial(material: Material, via: DirectedChannel[Material], task: Task)
	}
	//case class SubmittTask(task: Task)
	//case class AddMaterial(material: Material, via: DirectedChannel[Material], task: Task)

	// Implemented by Processor "Manager"
	trait ProcessorManager {
		def completedTask(task: Task, result: Material): Unit
	}

}
