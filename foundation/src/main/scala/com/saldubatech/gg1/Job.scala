package com.saldubatech.gg1

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Simulation.DomainSignal

object Job {

	case class Arrival(tick: Tick, jobId: String = java.util.UUID.randomUUID.toString) extends Identification.Impl(jobId) with Job
	case class Completion(tick: Tick, jobId: String) extends Identification.Impl() with Job

}

trait Job extends DomainSignal {

}
