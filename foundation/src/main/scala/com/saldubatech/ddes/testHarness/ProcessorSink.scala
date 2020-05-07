package com.saldubatech.ddes.testHarness

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.saldubatech.ddes.AgentTemplate.{Configure, Run}
import com.saldubatech.ddes.Clock
import com.saldubatech.ddes.Clock.{CompleteAction, StartActionOnReceive}
import com.saldubatech.ddes.Simulation.SimSignal
object ProcessorSink {

}
class ProcessorSink[DomainMessage](observer: ActorRef[(Clock.Tick, DomainMessage)], clock: Clock.Ref) {
	def init = Behaviors.setup[SimSignal]{
		implicit ctx => run
	}

	def run: Behavior[SimSignal] = Behaviors.receive[SimSignal]{
		(ctx, msg) => msg match {
			case cmd: Run[DomainMessage] =>
				ctx.log.debug(s"Processing Command: ${cmd.payload}")
//				ctx.log.info(s"MSC: ${cmd.from.path.name} -> ${ctx.self.path.name}: [${cmd.at}] ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.tick -> cmd.payload
				clock ! CompleteAction(cmd)
				run
			case cmd: Configure[DomainMessage] =>
				ctx.log.info(s"Configuring Command: ${cmd.payload}")
				clock ! StartActionOnReceive(cmd)
				observer ! cmd.tick -> cmd.payload
				clock ! CompleteAction(cmd)
				run
		}
	}
}
