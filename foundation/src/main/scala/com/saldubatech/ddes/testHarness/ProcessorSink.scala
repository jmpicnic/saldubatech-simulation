package com.saldubatech.ddes.testHarness

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate.{SourcedConfigure, SourcedRun}
import com.saldubatech.ddes.Clock
import com.saldubatech.ddes.Clock.{CompleteAction, StartActionOnReceive}
import com.saldubatech.ddes.Simulation.{DomainSignal, PSimSignal, SimRef, SimSignal}
object ProcessorSink {
	//case class Signal[DomainMessage <: DomainSignal](tick: Clock.Tick, notif: DomainMessage) extends Identification.Impl() with DomainSignal

	//type Signal[DomainMessage] = (Clock.Tick, DomainMessage)
}
class ProcessorSink[DomainMessage <: DomainSignal](observer: ActorRef[(Clock.Tick, DomainMessage)], clock: Clock.Ref) {
	import ProcessorSink._
	def init = Behaviors.setup[PSimSignal[DomainMessage]]{
		implicit ctx => run
	}

	def run: Behavior[PSimSignal[DomainMessage]] = Behaviors.receive[PSimSignal[DomainMessage]]{
		(ctx, msg) => msg match {
			case cmd: SourcedRun[_, DomainMessage] =>
				ctx.log.debug(s"Processing Command: ${cmd.payload}")
//				ctx.log.info(s"MSC: ${cmd.from.path.name} -> ${ctx.self.path.name}: [${cmd.at}] ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				observer ! (cmd.tick, cmd.payload)
				clock ! CompleteAction(cmd)
				run
			case cmd: SourcedConfigure[_, DomainMessage] =>
				ctx.log.info(s"Configuring Command: ${cmd.payload}")
				clock ! StartActionOnReceive(cmd)
				observer ! (cmd.tick, cmd.payload)
				clock ! CompleteAction(cmd)
				run
		}
	}
}
