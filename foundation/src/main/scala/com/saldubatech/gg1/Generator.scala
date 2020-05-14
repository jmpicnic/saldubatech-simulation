package com.saldubatech.gg1

import com.saldubatech.base.Identification
import com.saldubatech.base.randomvariables.Distributions
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}

object Generator {
	import com.saldubatech.ddes.Simulation.DomainSignal

	trait GeneratorSignal extends Job
	case class Configure(destination: SimRef[Job]) extends Identification.Impl() with GeneratorSignal
	case object Go extends Identification.Impl("Go-Signal") with GeneratorSignal
	case class NextArrival(interArrivalTime: Delay) extends Identification.Impl() with GeneratorSignal

	case class CompleteConfiguration(subject: SimRef[GeneratorSignal]) extends Identification.Impl() with DomainSignal
}

class Generator(val name: String, interArrivalTime: Distributions.LongRVar, until: Int) extends Identification.Impl() with AgentTemplate[Job, Generator] {
	import com.saldubatech.ddes.AgentTemplate._
	import Generator._
	import com.saldubatech.gg1.Job

	private var jobCount = 0
	private var _destination: SimRef[Job] = _
	lazy val destination: SimRef[Job] = _destination

	override def booter: DomainConfigure[SIGNAL] = new DomainConfigure[SIGNAL] {
		override def configure(config: Job)(implicit ctx:  FullSignallingContext[SIGNAL, _ <: DomainSignal]): DomainRun[SIGNAL] = config match {
			case Configure(dst) =>
				_destination = dst
				installManager(ctx.from)
				installSelf(ctx.aCtx.self)
				ctx.reply(CompleteConfiguration(ctx.aCtx.self))
				starting
		}
	}

	private val starting: RUNNER = {
		implicit ctx: CTX => {
			case Go =>
				val iat = interArrivalTime()
				ctx.signalSelf(NextArrival(iat), iat)
				runner
		}
	}
	private val runner: RUNNER = {
		implicit ctx: CTX => {
			case NextArrival(delay) =>
				ctx.signal(destination, Job.Arrival(ctx.now, s"Job_$jobCount"))
				if(jobCount < until) {
					jobCount += 1
					val iat = interArrivalTime()
					ctx.signalSelf(NextArrival(iat), iat)
					runner
				} else {
					done
				}
		}
	}
	private val done = AgentTemplate.DomainRun.noOp[SIGNAL]
}
