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
	type ProcessorRef = ActorRef[ProcessorMessage]

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

	case class CommandContext[DomainMessage](host: Processor[DomainMessage], from: ActorRef[ProcessorMessage], now: Tick, aCtx: ActorContext[ProcessorMessage])

	trait DomainRun[DomainMessage] {
		def process(processMessage: DomainMessage)(implicit ctx: CommandContext[DomainMessage]): Unit
	}
	trait DomainConfigure[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: CommandContext[DomainMessage]): Unit
	}

	trait Configuring[DomainMessage] {
		this: Processor[DomainMessage] =>
		protected val configuring: Configuring[DomainMessage] => DomainConfigure[DomainMessage]
		protected val controller: ActorRef[ControllerMessage]

		private lazy val configurer = configuring(this)


		def configure(run: Behavior[ProcessorMessage]) = Behaviors.receive[ProcessorMessage]{
			(ctx, msg) => msg match {
				case cmd: ConfigurationCommand[DomainMessage] =>
					ctx.log.debug(s"Configuring with $cmd")
					configurer.configure(cmd.cm)(CommandContext(this, cmd.from, cmd.at,  ctx))
					controller ! CompleteConfiguration(ctx.self)
					run
			}
		}

	}
	trait Running[DomainMessage] {this: Processor[DomainMessage] =>
		protected val clock: ActorRef[ClockMessage]
		val ref: Option[ProcessorRef]
		protected val running: Running[DomainMessage] => DomainRun[DomainMessage]

		private lazy val runner = running(this)

		def run = Behaviors.receive[ProcessorMessage]{
			(ctx, msg) => msg match {
				case cmd: ProcessCommand[DomainMessage] =>
					ctx.log.debug(s"Processing Command: ${cmd.dm}")
					receiveDomainCommand(cmd)
					runner.process(cmd.dm)(CommandContext(this, cmd.from, cmd.at, ctx))
					completeDomainCommand(cmd)
					Behaviors.same
				//case Shutdown => do something
			}
		}
		def receiveDomainCommand(cmd: ProcessCommand[DomainMessage]): Unit = clock ! StartActionOnReceive(cmd)
		def completeDomainCommand(cmd: ProcessCommand[DomainMessage]): Unit = clock ! CompleteAction(cmd)

		def tellTo[TargetDomainMessage](msg: TargetDomainMessage, to: ActorRef[ProcessorMessage/*[TargetDomainMessage]*/], delay: Option[Delay] = None)(implicit mCtx: CommandContext[DomainMessage]): Unit =
			clock ! Enqueue(to, mCtx.now + delay.getOrElse(0L), ProcessCommand(mCtx.aCtx.self, mCtx.now, msg))
		def tellSelf[TargetDomainMessage](msg: TargetDomainMessage, delay: Option[Delay] = None)(implicit mCtx: CommandContext[DomainMessage]): Unit = tellTo(msg, mCtx.aCtx.self, delay)

	}

}

class Processor[DomainMessage](processorName: String,
                                    override protected val clock: ActorRef[ClockMessage],
                                    override protected val controller: ActorRef[ControllerMessage],
                                    override protected val running: Processor.Running[DomainMessage] => Processor.DomainRun[DomainMessage],
                                    override protected val configuring: Processor.Configuring[DomainMessage] => Processor.DomainConfigure[DomainMessage]
                                   )
	extends Processor.Running[DomainMessage] with Processor.Configuring[DomainMessage]
{
	import Processor._

	lazy override val ref: Option[ProcessorRef] = refValue
	private def refValue: Option[ProcessorRef] = _ref
	private var _ref: Option[ProcessorRef] = None

	def init = Behaviors.setup[ProcessorMessage]{
		implicit ctx =>
			_ref = Some(ctx.self)
			controller ! RegisterProcessor(ctx.self)
			configure(run)
	}
}
