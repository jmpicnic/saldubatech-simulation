package com.saldubatech.ddes.testHarness

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock.{CompleteAction, StartActionOnReceive}
import com.saldubatech.ddes.Processor.{ConfigurationCommand, ProcessCommand, ProcessorMessage}
object ProcessorSink {

}
class ProcessorSink[DomainMessage](observer: ActorRef[(Clock.Tick, DomainMessage)], clock: Clock.ClockRef) {
	def init = Behaviors.setup[ProcessorMessage]{
		implicit ctx => run
	}

	def run: Behavior[ProcessorMessage] = Behaviors.receive[ProcessorMessage]{
		(ctx, msg) => msg match {
			case cmd: ProcessCommand[DomainMessage] =>
				ctx.log.debug(s"Processing Command: ${cmd.dm}")
//				ctx.log.info(s"MSC: ${cmd.from.path.name} -> ${ctx.self.path.name}: [${cmd.at}] ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.at -> cmd.dm
				clock ! CompleteAction(cmd)
				run
			case cmd: ConfigurationCommand[DomainMessage] =>
				ctx.log.info(s"Configuring Command: ${cmd.cm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.at -> cmd.cm
				clock ! CompleteAction(cmd)
				run
		}
	}
}
