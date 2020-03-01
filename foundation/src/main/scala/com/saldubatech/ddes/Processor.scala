/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.ddes.Clock.{ClockMessage, CompleteAction, Delay, Enqueue, StartActionOnReceive, Tick}
import com.saldubatech.ddes.Simulation.{Command, Notification}
import com.saldubatech.ddes.SimulationController.ControllerMessage

object Processor {
	trait ProcessorMessage
	type ProcessorBehavior = Behavior[ProcessorMessage]
	type ProcessorRef = ActorRef[ProcessorMessage/*[TargetDomainMessage]*/]

	trait ProcessorCommand extends Command with ProcessorMessage
	trait ProcessorControlCommand extends ProcessorCommand
	case object ProcessorShutdown extends ProcessorControlCommand
	trait ProcessorDomainCommand extends ProcessorCommand
	class ActionCommand(val from: ActorRef[ProcessorMessage], val at: Tick, val uid: String = java.util.UUID.randomUUID.toString) extends ProcessorDomainCommand
	case class ConfigurationCommand[ConfigurationMessage](override val from: ActorRef[ProcessorMessage], override val at: Tick, cm: ConfigurationMessage) extends ActionCommand(from, at)
	case class ProcessCommand[DomainMessage](override val from: ActorRef[ProcessorMessage], override val at: Tick, dm: DomainMessage) extends ActionCommand(from, at)

	trait ProcessorNotification extends Notification with ControllerMessage
	case class RegisterProcessor(p: ActorRef[ProcessorMessage]) extends ProcessorNotification
	case class CompleteConfiguration(p: ActorRef[ProcessorMessage]) extends ProcessorNotification


	case class CommandContext[DomainMessage](from: ProcessorRef, now: Tick, aCtx: ActorContext[ProcessorMessage])(implicit clock: ActorRef[ClockMessage]) {
		private def doTell[TargetDomainMessage](to: ProcessorRef, msg: TargetDomainMessage, delay: Option[Delay]): Unit =
			clock ! Clock.Enqueue(to, now + delay.getOrElse(0L), ProcessCommand(aCtx.self, now + delay.getOrElse(0L), msg))

		def teller[TargetDomainMessage](to: ProcessorRef): (TargetDomainMessage, Option[Delay]) => Unit = (m, d) => doTell(to, m, d)
		def tellTo[TargetDomainMessage](to: ProcessorRef, msg: TargetDomainMessage): Unit = doTell(to, msg, None)
		def tellTo[TargetDomainMessage](to: ProcessorRef, msg: TargetDomainMessage, delay: Delay): Unit = doTell(to, msg, Some(delay))
		def tellSelf(msg: DomainMessage) = doTell(aCtx.self, msg, None)
		def tellSelf(msg: DomainMessage, delay: Delay) = doTell(aCtx.self, msg, Some(delay))
		def reply[TargetDomainMessage](msg: TargetDomainMessage, delay: Delay) = doTell(from, msg, Some(delay))
		def reply[TargetDomainMessage](msg: TargetDomainMessage) = doTell(from, msg, None)
	}

	trait DomainRun[DomainMessage] {
		def process(processMessage: DomainMessage)(implicit ctx: CommandContext[DomainMessage]): DomainRun[DomainMessage]

	}
	trait DomainConfigure[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: CommandContext[DomainMessage]): DomainRun[DomainMessage]
	}

	def behaviorizeRunner[DomainMessage](runner: DomainRun[DomainMessage])(implicit clock: ActorRef[ClockMessage]): Behavior[ProcessorMessage] = Behaviors.receive[ProcessorMessage] {
		(ctx, msg) => msg match {
			case cmd: ProcessCommand[DomainMessage] =>
				ctx.log.debug(s"Processing Command: ${cmd.dm}")
				clock ! StartActionOnReceive(cmd)
				val next = runner.process(cmd.dm)(CommandContext(cmd.from, cmd.at, ctx)(clock))
				clock ! CompleteAction(cmd)
				behaviorizeRunner(next)
			//case Shutdown => do something
		}
	}

}

class Processor[DomainMessage](val processorName: String,
                                    protected val clock: ActorRef[ClockMessage],
                                    protected val controller: ActorRef[ControllerMessage],
                                    protected val configurer: Processor.DomainConfigure[DomainMessage]
                                   )
{
	import Processor._

	lazy val ref: Option[ProcessorRef] = refValue
	private def refValue: Option[ProcessorRef] = _ref
	private var _ref: Option[ProcessorRef] = None

	def init = Behaviors.setup[ProcessorMessage]{
		implicit ctx =>
			_ref = Some(ctx.self)
			controller ! RegisterProcessor(ctx.self)
			configure
	}

	def configure = Behaviors.receive[ProcessorMessage]{
		(ctx, msg) => msg match {
			case cmd: ConfigurationCommand[DomainMessage] =>
				ctx.log.debug(s"Configuring with $cmd")
				val run = configurer.configure(cmd.cm)(CommandContext(cmd.from, cmd.at, ctx)(clock))
				controller ! CompleteConfiguration(ctx.self)
				behaviorizeRunner(run)(clock)
		}
	}
}
