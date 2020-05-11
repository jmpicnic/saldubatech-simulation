package com.saldubatech.test

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.AgentTemplate.{SourcedConfigure, SourcedRun}
import com.saldubatech.ddes.Clock
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}

trait ClockEnabled {
	val testKit: ActorTestKit
	lazy val clock = testKit.spawn(Clock())

	def startTime(at: Tick = 0L) = clock ! Clock.StartTime(at)

	def enqueue[SourceMsg <: DomainSignal, DomainMessage <: DomainSignal](to: SimRef[DomainMessage], from: SimRef[SourceMsg], at: Tick, signal: DomainMessage): Unit =
		clock ! Clock.Enqueue(to, SourcedRun(from, at, signal))

	def enqueueConfigure[ConfigureMessage <: DomainSignal](to: SimRef[ConfigureMessage], from: SimRef[_ <: DomainSignal], at: Tick, signal: ConfigureMessage): Unit =
		clock ! Clock.Enqueue(to, SourcedConfigure(from, at, signal))
}
