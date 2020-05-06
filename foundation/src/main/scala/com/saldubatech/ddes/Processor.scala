/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, CompleteAction, Delay, Enqueue, StartActionOnReceive, Tick}
import com.saldubatech.ddes.Simulation.{ControllerMessage, EngineSignal, Notification, Signal, DomainSignal, SimRef, SimSignal}
import com.saldubatech.util.LogEnabled

object Processor {
	type CTX = ActorContext[SimSignal]

	trait ProcessorMessage extends SimSignal
	type ProcessorBehavior = Behavior[ProcessorMessage]
	type ProcessorCreator = {def spawn[T](behavior: Behavior[T], name: String): ActorRef[T]}


	trait ProcessorControlCommand extends EngineSignal
	case object ProcessorShutdown extends Identification.Impl() with ProcessorControlCommand

	trait ProcessorCommand extends ProcessorMessage
	class ActionCommand(override val from: SimRef, val tick: Tick) extends Identification.Impl() with ProcessorCommand
	case class ConfigurationCommand[ConfigurationMessage](override val from: SimRef, override val tick: Tick, cm: ConfigurationMessage) extends ActionCommand(from, tick)
	case class ProcessCommand[DomainMessage <: DomainSignal](override val from: SimRef, override val tick: Tick, dm: DomainMessage) extends ActionCommand(from, tick)

	trait ProcessorNotification extends ControllerMessage
	case class RegisterProcessor(p: SimRef) extends Identification.Impl() with ProcessorNotification
	class BaseCompleteConfiguration(p: SimRef) extends Identification.Impl() with ProcessorNotification
	case class CompleteConfiguration(p: SimRef) extends BaseCompleteConfiguration(p: SimRef)
	case class Run(tick: Tick) extends Identification.Impl() with ProcessorNotification

	trait SignallingContext[-DomainMessage  <: DomainSignal] extends LogEnabled {
		val from: SimRef
		val now: Tick
		val aCtx: ActorContext[SimSignal]
		implicit val clock: Clock.Ref

		protected def wrap[TargetDomainMessage <: DomainSignal](to: SimRef, at: Tick, msg: TargetDomainMessage): ActionCommand

		private def doTell[TargetDomainMessage <: DomainSignal](to: SimRef, msg: TargetDomainMessage, delay: Option[Delay]): Unit = {
//			log.info(s"MSC: ${from.path.name} -> ${to.path.name}: [${now}::${now+delay.getOrElse(0L)}] ${msg}")
			clock ! Clock.Enqueue(to, wrap(aCtx.self, now + delay.getOrElse(0L), msg))
		}

		def signaller[TargetDomainMessage <: DomainSignal](to: SimRef): (TargetDomainMessage, Option[Delay]) => Unit = (m, d) => doTell(to, m, d)
		def signal[TargetDomainMessage <: DomainSignal](to: SimRef, msg: TargetDomainMessage): Unit = doTell(to, msg, None)
		def signal[TargetDomainMessage <: DomainSignal](to: SimRef, msg: TargetDomainMessage, delay: Delay): Unit = doTell(to, msg, Some(delay))
		def signalSelf(msg: DomainMessage) = doTell(aCtx.self, msg, None)
		def signalSelf(msg: DomainMessage, delay: Delay) = doTell(aCtx.self, msg, Some(delay))
		def reply[TargetDomainMessage <: DomainSignal](msg: TargetDomainMessage, delay: Delay) = doTell(from, msg, Some(delay))
		def reply[TargetDomainMessage <: DomainSignal](msg: TargetDomainMessage) = doTell(from, msg, None)

		def configureContext = ConfigureContext(from, now, aCtx)
		def commandContext = CommandContext(from, now, aCtx)
	}


	case class CommandContext[-DomainMessage <: DomainSignal](override val from: SimRef, override val now: Tick, override val aCtx: ActorContext[SimSignal])(implicit override val clock: Clock.Ref) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetDomainMessage <: DomainSignal](to: SimRef, at: Tick, msg: TargetDomainMessage): ActionCommand = ProcessCommand(aCtx.self, at, msg)
	}

	case class ConfigureContext[-DomainMessage <: DomainSignal](override val from: SimRef, override val now: Tick, override val aCtx: ActorContext[SimSignal])(implicit override val clock: Clock.Ref) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetDomainMessage <: DomainSignal](to: SimRef, at: Tick, msg: TargetDomainMessage): ActionCommand = ConfigurationCommand(aCtx.self, at, msg)
	}

	trait DomainMessageProcessor[DomainMessage <: DomainSignal]
	object DomainMessageProcessor {
	}

	trait DomainRun[DomainMessage <: DomainSignal] extends Function[SignallingContext[DomainMessage], PartialFunction[DomainMessage,DomainRun[DomainMessage]]] with DomainMessageProcessor[DomainMessage] {
		def orElse(other: DomainRun[DomainMessage]): DomainRun[DomainMessage] = (ctx: SignallingContext[DomainMessage]) => this (ctx) orElse other(ctx)

		def isDefinedAt(s: DomainMessage) = (ctx: SignallingContext[DomainMessage]) => this(ctx).isDefinedAt(s)
	}
	object DomainRun {
		def apply[DomainMessage <: DomainSignal](runLogic: PartialFunction[DomainMessage, DomainRun[DomainMessage]]): DomainRun[DomainMessage] = {
			implicit ctx: SignallingContext[DomainMessage] => runLogic
		}

		class Same[DomainMessage <: DomainSignal] extends Processor.DomainRun[DomainMessage] {
			override def apply(ctx: SignallingContext[DomainMessage]): PartialFunction[DomainMessage, DomainRun[DomainMessage]] = {
				case any =>
					throw new IllegalStateException(s"The 'Same' DomainRun should never be active: Received signal: $any from ${ctx.from} as part of ${ctx.aCtx.self}")
					this
			}
		}
		def noOp[DomainMessage <: DomainSignal] = Processor.DomainRun[DomainMessage]{
			case n: Any if false => Processor.DomainRun.same
		}

		def same[DomainMessage <: DomainSignal] = new Same[DomainMessage]

	}

	trait DomainConfigure[DomainMessage <: DomainSignal] extends DomainMessageProcessor[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: SignallingContext[DomainMessage]): DomainMessageProcessor[DomainMessage]
	}

}

class Processor[DomainMessage <: DomainSignal](val processorName: String,
                                               protected val clock: Clock.Ref,
                                               protected val controller: SimulationController.Ref,
                                               protected val initialConfigurer: Processor.DomainConfigure[DomainMessage]
                              ) extends LogEnabled {
	import Processor._

	lazy val ref: SimRef = _ref.head
	private var _ref: Option[SimRef] = None

	def init = Behaviors.setup[SimSignal]{
		implicit ctx =>
			_ref = Some(ctx.self)
			controller ! RegisterProcessor(ctx.self)
			doConfigure(initialConfigurer)
	}


	private def doConfigure(c: Processor.DomainConfigure[DomainMessage]): Behaviors.Receive[SimSignal] = Behaviors.receive[SimSignal]{
		(ctx, msg) => msg match {
			case cmd: ConfigurationCommand[DomainMessage] =>
				ctx.log.debug(s"Configuring with $cmd")
				clock ! StartActionOnReceive(cmd)
				val next = c.configure(cmd.cm)(ConfigureContext(cmd.from, cmd.tick, ctx)(clock))
				clock ! CompleteAction(cmd)
				next match {
					case configurer: DomainConfigure[DomainMessage] =>
						doConfigure(configurer)
					case runner: DomainRun[DomainMessage] =>
						controller ! CompleteConfiguration(ref)
						behaviorizeRunner(runner)(clock)
				}

		}
	}
	private def behaviorizeRunner(runner: DomainRun[DomainMessage])(implicit clock: Clock.Ref): Behavior[SimSignal] =
		Behaviors.receive[SimSignal]{
			(ctx, msg) =>
				msg match {
					case cmd: ProcessCommand[DomainMessage] => doProcessCommand(runner)(cmd)(clock, ctx)
					//case Shutdown => do something
				}
		}

	private def doProcessCommand(runner: DomainRun[DomainMessage])(cmd: ProcessCommand[DomainMessage])(implicit clock: Clock.Ref, ctx: CTX): Behavior[SimSignal] = {
		ctx.log.debug(s"Processing Command: ${cmd.dm} at ${cmd.tick}")
		clock ! StartActionOnReceive(cmd)
		val next: DomainRun[DomainMessage] = runner(CommandContext(cmd.from, cmd.tick, ctx)(clock))(cmd.dm)
		clock ! CompleteAction(cmd)
		next match {
			case r: DomainRun.Same[DomainMessage] =>
				behaviorizeRunner(runner)
			case other => behaviorizeRunner(next)
		}
	}
}
