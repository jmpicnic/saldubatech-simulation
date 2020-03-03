package com.saldubatech.ddes.testHarness

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.saldubatech.ddes.Clock
import com.saldubatech.ddes.Clock.{CompleteAction, StartActionOnReceive}
import com.saldubatech.ddes.Processor.{ProcessCommand, ProcessorMessage}

class ProcessorSink[DomainMessage](observer: ActorRef[(Clock.Tick, DomainMessage)], clock: Clock.ClockRef) {
	def init = Behaviors.setup[ProcessorMessage]{
		implicit ctx => run
	}

	def run: Behavior[ProcessorMessage] = Behaviors.receive[ProcessorMessage]{
		(ctx, msg) => msg match {
			case cmd: ProcessCommand[DomainMessage] =>
				val dm = cmd.dm
				ctx.log.debug(s"Processing Command: ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.at -> cmd.dm
				clock ! CompleteAction(cmd)
				run
		}
	}
}
