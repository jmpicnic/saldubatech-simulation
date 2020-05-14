package com.saldubatech.gg1

import com.saldubatech.base.Identification
import com.saldubatech.base.randomvariables.Distributions
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}

object JobProcessor {
	import com.saldubatech.ddes.Simulation.DomainSignal

	case class Configure(destination: SimRef[Job]) extends Identification.Impl() with Job
	case class CompleteJob(interArrivalTime: Delay, jobId: String) extends Identification.Impl(jobId) with Job

	case class CompleteConfiguration(subject: SimRef[Job]) extends Identification.Impl() with DomainSignal
}

class JobProcessor(val name: String, processingTime: Distributions.LongRVar) extends Identification.Impl() with AgentTemplate[Job, JobProcessor] {
	import JobProcessor._
	import com.saldubatech.ddes.AgentTemplate._

	private var _destination: SimRef[Job] = _
	lazy val destination: SimRef[Job] = _destination

	override def booter: DomainConfigure[SIGNAL] = new DomainConfigure[SIGNAL] {
		override def configure(config: SIGNAL)(implicit ctx:  CTX): DomainRun[Job] = config match {
			case Configure(dst) =>
				_destination = dst
				installManager(ctx.from)
				installSelf(ctx.aCtx.self)
				ctx.reply(CompleteConfiguration(ctx.aCtx.self))
				runner
		}
	}

	private val runner: RUNNER = {
		implicit ctx: CTX => {
			case Job.Arrival(tick, jobId) =>
				val processTime = processingTime()
				ctx.signalSelf(CompleteJob(processTime, jobId), processTime)
				runner
			case CompleteJob(delay, jobId) =>
				ctx.signal(destination, Job.Completion(ctx.now, jobId))
				runner
		}
	}
}
