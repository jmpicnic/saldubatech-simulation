/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, CompleteAction, Delay, Enqueue, StartActionOnReceive, Tick}
import com.saldubatech.ddes.Simulation.{Command, Notification}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.util.LogEnabled

object Processor {
	trait ProcessorMessage
	type ProcessorBehavior = Behavior[ProcessorMessage]
	type Ref = ActorRef[ProcessorMessage/*[TargetDomainMessage]*/]
	type ProcessorCreator = {def spawn[T](behavior: Behavior[T], name: String): ActorRef[T]}

	trait ProcessorCommand extends Command with ProcessorMessage
	trait ProcessorControlCommand extends ProcessorCommand
	case object ProcessorShutdown extends ProcessorControlCommand
	class ActionCommand(val from: ActorRef[ProcessorMessage], val at: Tick, val uid: String = java.util.UUID.randomUUID.toString) extends ProcessorCommand
	case class ConfigurationCommand[ConfigurationMessage](override val from: ActorRef[ProcessorMessage], override val at: Tick, cm: ConfigurationMessage) extends ActionCommand(from, at)
	case class ProcessCommand[DomainMessage](override val from: ActorRef[ProcessorMessage], override val at: Tick, dm: DomainMessage) extends ActionCommand(from, at)

	trait ProcessorNotification extends Notification with Identification with ControllerMessage
	case class RegisterProcessor(p: ActorRef[ProcessorMessage]) extends Identification.Impl() with ProcessorNotification
	class BaseCompleteConfiguration(p: ActorRef[ProcessorMessage]) extends Identification.Impl() with ProcessorNotification
	case class CompleteConfiguration(p: Ref) extends BaseCompleteConfiguration(p: Ref)
	case class Run(at: Tick) extends Identification.Impl() with ProcessorNotification

	trait SignallingContext[-DomainMessage] extends LogEnabled {
		val from: Ref
		val now: Tick
		val aCtx: ActorContext[ProcessorMessage]
		implicit val clock: ActorRef[ClockMessage]

		protected def wrap[TargetDomainMessage](to: ActorRef[ProcessorMessage], at: Tick, msg: TargetDomainMessage): ActionCommand

		private def doTell[TargetDomainMessage](to: Ref, msg: TargetDomainMessage, delay: Option[Delay]): Unit = {
//			log.info(s"MSC: ${from.path.name} -> ${to.path.name}: [${now}::${now+delay.getOrElse(0L)}] ${msg}")
			clock ! Clock.Enqueue(to, wrap(aCtx.self, now + delay.getOrElse(0L), msg))
		}

		def signaller[TargetDomainMessage](to: Ref): (TargetDomainMessage, Option[Delay]) => Unit = (m, d) => doTell(to, m, d)
		def signal[TargetDomainMessage](to: Ref, msg: TargetDomainMessage): Unit = doTell(to, msg, None)
		def signal[TargetDomainMessage](to: Ref, msg: TargetDomainMessage, delay: Delay): Unit = doTell(to, msg, Some(delay))
		def signalSelf(msg: DomainMessage) = doTell(aCtx.self, msg, None)
		def signalSelf(msg: DomainMessage, delay: Delay) = doTell(aCtx.self, msg, Some(delay))
		def reply[TargetDomainMessage](msg: TargetDomainMessage, delay: Delay) = doTell(from, msg, Some(delay))
		def reply[TargetDomainMessage](msg: TargetDomainMessage) = doTell(from, msg, None)

		def configureContext = ConfigureContext(from, now, aCtx)
		def commandContext = CommandContext(from, now, aCtx)
	}


	case class CommandContext[-DomainMessage](override val from: Ref, override val now: Tick, override val aCtx: ActorContext[ProcessorMessage])(implicit override val clock: ActorRef[ClockMessage]) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetDomainMessage](to: ActorRef[ProcessorMessage], at: Tick, msg: TargetDomainMessage): ActionCommand = ProcessCommand(aCtx.self, at, msg)
	}

	case class ConfigureContext[-DomainMessage](override val from: Ref, override val now: Tick, override val aCtx: ActorContext[ProcessorMessage])(implicit override val clock: ActorRef[ClockMessage]) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetDomainMessage](to: ActorRef[ProcessorMessage], at: Tick, msg: TargetDomainMessage): ActionCommand = ConfigurationCommand(aCtx.self, at, msg)
	}

	trait DomainMessageProcessor[DomainMessage]
	object DomainMessageProcessor {
	}

	//trait DomainRun[DomainMessage] extends PartialFunction[DomainMessage, Function1[CommandContext[DomainMessage],DomainRun[DomainMessage]]]
	trait DomainRun[DomainMessage] extends Function[SignallingContext[DomainMessage], PartialFunction[DomainMessage,DomainRun[DomainMessage]]] with DomainMessageProcessor[DomainMessage] {
		def orElse(other: DomainRun[DomainMessage]): DomainRun[DomainMessage] = (ctx: SignallingContext[DomainMessage]) => this (ctx) orElse other(ctx)
	}
	object DomainRun {
		def apply[DomainMessage](runLogic: PartialFunction[DomainMessage, DomainRun[DomainMessage]]): DomainRun[DomainMessage] = {
			implicit ctx: SignallingContext[DomainMessage] => runLogic
		}

		class Same[DomainMessage] extends Processor.DomainRun[DomainMessage] {
			override def apply(ctx: SignallingContext[DomainMessage]): PartialFunction[DomainMessage, DomainRun[DomainMessage]] = {
				case any =>
					throw new IllegalStateException(s"The 'Same' DomainRun should never be active: Received signal: $any from ${ctx.from} as part of ${ctx.aCtx.self}")
					this
			}
		}
		def noOp[DomainMessage] = Processor.DomainRun[DomainMessage]{
			case n: Any if false => Processor.DomainRun.same
		}

		def same[DomainMessage] = new Same[DomainMessage]

	}

	trait DomainConfigure[DomainMessage] extends DomainMessageProcessor[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: SignallingContext[DomainMessage]): DomainMessageProcessor[DomainMessage]
	}

	type DelayedDomainRun[DomainSignal] = SignallingContext[DomainSignal] => Processor.DomainRun[DomainSignal]


}

class Processor[DomainMessage](val processorName: String,
                               protected val clock: ActorRef[ClockMessage],
                               protected val controller: ActorRef[ControllerMessage],
                               protected val initialConfigurer: Processor.DomainConfigure[DomainMessage]
                              ) extends LogEnabled {
	import Processor._

	lazy val ref: Ref = _ref.head
	private var _ref: Option[Ref] = None

	def init = Behaviors.setup[ProcessorMessage]{
		implicit ctx =>
			_ref = Some(ctx.self)
			controller ! RegisterProcessor(ctx.self)
			doConfigure(initialConfigurer)
	}


	private def doConfigure(c: Processor.DomainConfigure[DomainMessage]): Behaviors.Receive[ProcessorMessage] = Behaviors.receive[ProcessorMessage]{
		(ctx, msg) => msg match {
			case cmd: ConfigurationCommand[DomainMessage] =>
				ctx.log.debug(s"Configuring with $cmd")
				clock ! StartActionOnReceive(cmd)
				val next = c.configure(cmd.cm)(CommandContext(cmd.from, cmd.at, ctx)(clock))
				clock ! CompleteAction(cmd)
				next match {
					case configurer: DomainConfigure[DomainMessage] =>
						doConfigure(configurer)
					case runner: DomainRun[DomainMessage] =>
						controller ! CompleteConfiguration(ref)
						behaviorizeRunner[DomainMessage](runner)(clock)
				}

		}
	}
	private def behaviorizeRunner[DomainMessage](runner: DomainRun[DomainMessage])(implicit clock: ActorRef[ClockMessage]): Behavior[ProcessorMessage] =
		Behaviors.receive[ProcessorMessage]{
			(ctx, msg) =>
				msg match {
					case cmd: ProcessCommand[DomainMessage] =>
//						ctx.log.info(s"MSC: ${cmd.from.path.name} -> ${ctx.self.path.name}: [${cmd.at}] ${cmd.dm}")
						ctx.log.debug(s"Processing Command: ${cmd.dm} at ${cmd.at}")
						clock ! StartActionOnReceive(cmd)
						val next: DomainRun[DomainMessage] = runner(CommandContext(cmd.from, cmd.at, ctx)(clock))(cmd.dm)
//						ctx.log.debug(s"Done Processing Command: ${cmd.dm} at ${cmd.at} by ${ctx.self}")
						clock ! CompleteAction(cmd)
						next match {
							case r: DomainRun.Same[DomainMessage] =>
//								log.debug(s"Repeating DomainRun")
								behaviorizeRunner(runner)
							case other => behaviorizeRunner(next)
						}
					//case Shutdown => do something
				}
		}
}
