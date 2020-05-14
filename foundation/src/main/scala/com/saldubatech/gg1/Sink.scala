package com.saldubatech.gg1

import com.saldubatech.base.Identification
import com.saldubatech.base.randomvariables.Distributions
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.Simulation.SimRef
import com.saldubatech.util.LogEnabled

object Sink {
	import com.saldubatech.ddes.Simulation.DomainSignal

	case object Configure extends Identification.Impl() with Job


	case class CompleteConfiguration(subject: SimRef[Job]) extends Identification.Impl() with DomainSignal
}

class Sink(val name: String, receiveJob: (Job, Tick) => Unit) extends Identification.Impl() with AgentTemplate[Job, Sink] with LogEnabled {
	import Sink._
	import com.saldubatech.ddes.AgentTemplate._

	private var _destination: SimRef[Job] = _

	override def booter: DomainConfigure[SIGNAL] = new DomainConfigure[SIGNAL] {
		override def configure(config: SIGNAL)(implicit ctx:  CTX): DomainRun[Job] = config match {
			case Configure =>
				installManager(ctx.from)
				installSelf(ctx.aCtx.self)
				ctx.reply(CompleteConfiguration(ctx.aCtx.self))
				runner
		}
	}

	private val runner: RUNNER = {
		implicit ctx: CTX => {
			case jb@Job.Completion(tick, jobId) =>
				log.info(s"Completed Job $jobId at ${ctx.now}")
				receiveJob(jb, ctx.now)
				runner
		}
	}
}
