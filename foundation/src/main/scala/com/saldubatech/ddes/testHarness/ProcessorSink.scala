package com.saldubatech.ddes.testHarness

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock.{CompleteAction, StartActionOnReceive}
import com.saldubatech.ddes.Processor.{ConfigurationCommand, ProcessCommand, ProcessorMessage}
import com.saldubatech.ddes.Simulation.SimSignal
object ProcessorSink {

}
class ProcessorSink[DomainMessage](observer: ActorRef[(Clock.Tick, DomainMessage)], clock: Clock.Ref) {
	def init = Behaviors.setup[SimSignal]{
		implicit ctx => run
	}

	def run: Behavior[SimSignal] = Behaviors.receive[SimSignal]{
		(ctx, msg) => msg match {
			case cmd: ProcessCommand[DomainMessage] =>
				ctx.log.debug(s"Processing Command: ${cmd.dm}")
//				ctx.log.info(s"MSC: ${cmd.from.path.name} -> ${ctx.self.path.name}: [${cmd.at}] ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.tick -> cmd.dm
				clock ! CompleteAction(cmd)
				run
			case cmd: ConfigurationCommand[DomainMessage] =>
				ctx.log.info(s"Configuring Command: ${cmd.cm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.tick -> cmd.cm
				clock ! CompleteAction(cmd)
				run
		}
	}
}
