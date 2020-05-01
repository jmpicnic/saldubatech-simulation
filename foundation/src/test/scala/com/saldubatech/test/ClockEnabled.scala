package com.saldubatech.test

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.{Clock, Processor}

trait ClockEnabled {
	val testKit: ActorTestKit
	lazy val clock = testKit.spawn(Clock())

		def startTime(at: Tick = 0L) = clock ! Clock.StartTime(at)

	def enqueue[DomainMessage](to: Processor.Ref, from: Processor.Ref, at: Tick, signal: DomainMessage): Unit =
		clock ! Clock.Enqueue(to, Processor.ProcessCommand(from, at, signal))

	def enqueueConfigure[ConfigureMessage](to: Processor.Ref, from: Processor.Ref, at: Tick, signal: ConfigureMessage): Unit =
		clock ! Clock.Enqueue(to, Processor.ConfigurationCommand(from, at, signal))
}
