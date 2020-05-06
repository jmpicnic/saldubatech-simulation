/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, SimRef, SimSignal}
import com.saldubatech.util.LogEnabled

object AgentTemplate {

	type ProcessorBehavior[PAYLOAD <: DomainSignal] = Behavior[SimSignal]
	type Ref = ActorRef[SimSignal]
	type AgentCreator = {def spawn[T](behavior: Behavior[T], name: String): ActorRef[T]}

	case class Configure[ConfigurationMessage <: DomainSignal]
	(override val from: ActorRef[SimSignal], override val tick: Tick, payload: ConfigurationMessage) extends Identification.Impl with SimSignal
	case class Run[DomainMessage <: DomainSignal]
	(override val from: Ref, override val tick: Tick, payload: DomainMessage) extends Identification.Impl() with SimSignal

	abstract class AgentNotification[PAYLOAD <: DomainSignal] extends Identification.Impl() with ControllerMessage {
		val subject: Ref
	}
	case class RegisterProcessor[PAYLOAD <: DomainSignal](override val subject: Ref) extends AgentNotification[PAYLOAD]
	class BaseCompleteConfiguration[PAYLOAD <: DomainSignal](override val subject: Ref) extends AgentNotification[PAYLOAD]
	case class CompleteConfiguration[PAYLOAD <: DomainSignal](override val subject: Ref) extends AgentNotification[PAYLOAD]


	trait SignallingContext[DomainMessage <: DomainSignal] extends LogEnabled {
		val from: Ref
		val now: Tick
		val aCtx: ActorContext[SimSignal]
		implicit val clock: ActorRef[ClockMessage]

		protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): SimSignal

		private def doTell(to: Ref, msg: DomainSignal, delay: Option[Delay]): Unit = clock ! Clock.Enqueue(to, wrap(now + delay.getOrElse(0L), msg))

		def signaller(to: Ref): (DomainSignal, Option[Delay]) => Unit = (m, d) => doTell(to, m, d)
		def signal(to: Ref, msg: DomainSignal): Unit = doTell(to, msg, None)
		def signal(to: Ref, msg: DomainSignal, delay: Delay): Unit = doTell(to, msg, Some(delay))
		def signalSelf(msg: DomainMessage) = doTell(aCtx.self, msg, None)
		def signalSelf(msg: DomainMessage, delay: Delay) = doTell(aCtx.self, msg, Some(delay))
		def reply(msg: DomainSignal, delay: Delay) = doTell(from, msg, Some(delay))
		def reply(msg: DomainSignal) = doTell(from, msg, None)

		def configureContext[FROM_PAYLOAD] = ConfigureContext(from, now, aCtx)
		def commandContext[FROM_PAYLOAD] = CommandContext(from, now, aCtx)
	}


	case class CommandContext[DomainMessage <: DomainSignal](override val from: Ref, override val now: Tick, override val aCtx: ActorContext[SimSignal])
	                                                        (implicit override val clock: ActorRef[ClockMessage]) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): SimSignal = Run(aCtx.self, at, msg)
	}

	case class ConfigureContext[DomainMessage <: DomainSignal](override val from: Ref, override val now: Tick, override val aCtx: ActorContext[SimSignal])
	                                                          (implicit override val clock: ActorRef[ClockMessage]) extends SignallingContext[DomainMessage] {
		override protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): SimSignal = Configure(aCtx.self, at, msg)
	}

	trait DomainMessageProcessor[DomainMessage]
	object DomainMessageProcessor {
	}

	//trait DomainRun[DomainMessage] extends PartialFunction[DomainMessage, Function1[CommandContext[DomainMessage],DomainRun[DomainMessage]]]
	trait DomainRun[DomainMessage <: DomainSignal] extends Function[SignallingContext[DomainMessage], PartialFunction[DomainMessage,DomainRun[DomainMessage]]] with DomainMessageProcessor[DomainMessage] {
		def orElse(other: DomainRun[DomainMessage]): DomainRun[DomainMessage] = (ctx: SignallingContext[DomainMessage]) => this (ctx) orElse other(ctx)

		def isDefinedAt(s: DomainMessage) = (ctx: SignallingContext[DomainMessage]) => this(ctx).isDefinedAt(s)
	}
	object DomainRun {
		def apply[DomainMessage <: DomainSignal](runLogic: PartialFunction[DomainMessage, DomainRun[DomainMessage]]): DomainRun[DomainMessage] = {
			implicit ctx: SignallingContext[DomainMessage] => runLogic
		}

		class Same[DomainMessage <: DomainSignal] extends DomainRun[DomainMessage] {
			override def apply(ctx: SignallingContext[DomainMessage]): PartialFunction[DomainMessage, DomainRun[DomainMessage]] = {
				case any =>
					throw new IllegalStateException(s"The 'Same' DomainRun should never be active: Received signal: $any from ${ctx.from} as part of ${ctx.aCtx.self}")
					this
			}
		}
		def noOp[DomainMessage <: DomainSignal] = Processor.DomainRun[DomainMessage]{
			case n: Any if false => Processor.DomainRun.same
		}

		def same[DomainMessage <: DomainSignal]: DomainRun[DomainMessage] = new Same[DomainMessage]

	}

	trait DomainConfigure[DomainMessage <: DomainSignal] extends DomainMessageProcessor[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: SignallingContext[DomainMessage]): DomainMessageProcessor[DomainMessage]
	}

	class Wrapper[DomainMessage <: DomainSignal](val name: String,
	                                             protected val clock: ActorRef[ClockMessage],
	                                             protected val controller: ActorRef[ControllerMessage],
	                                             protected val initialConfigurer: AgentTemplate.DomainConfigure[DomainMessage]
	                            ) extends LogEnabled {

		lazy val ref: Ref = _ref.head
		private var _ref: Option[Ref] = None

		def init = Behaviors.setup[SimSignal]{
			implicit ctx =>
				_ref = Some(ctx.self)
				controller ! RegisterProcessor(ctx.self)
				doConfigure(initialConfigurer)
		}


		private def doConfigure(c: DomainConfigure[DomainMessage]): Behaviors.Receive[SimSignal] = Behaviors.receive[SimSignal]{
			(ctx, msg) => msg match {
				case cmd: Configure[DomainMessage] =>
					ctx.log.debug(s"Configuring with $cmd")
					clock ! StartActionOnReceive(cmd)

					implicit val iCtx = ConfigureContext[DomainMessage](cmd.from, cmd.tick, ctx)(clock)
					val a = c.configure(cmd.payload)

					val next = c.configure(cmd.payload)(ConfigureContext[DomainMessage](cmd.from, cmd.tick, ctx)(clock))
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
		private def behaviorizeRunner(runner: DomainRun[DomainMessage])(implicit clock: ActorRef[ClockMessage]): Behavior[SimSignal] =
			Behaviors.receive[SimSignal]{
				(ctx, msg) =>
					msg match {
						case cmd: Run[DomainMessage] => doProcessCommand(runner)(cmd)(clock, ctx)
						//case Shutdown => do something
					}
			}

		private def doProcessCommand(runner: DomainRun[DomainMessage])(cmd: Run[DomainMessage])(implicit clock: ActorRef[ClockMessage], ctx: ActorContext[SimSignal]): Behavior[SimSignal] = {
			ctx.log.debug(s"Processing Command: ${cmd.payload} at ${cmd.tick}")
			clock ! StartActionOnReceive(cmd)
			val next: DomainRun[DomainMessage] = runner(CommandContext(cmd.from, cmd.tick, ctx)(clock))(cmd.payload)
			clock ! CompleteAction(cmd)
			next match {
				case r: DomainRun.Same[DomainMessage] =>
					behaviorizeRunner(runner)
				case other => behaviorizeRunner(next)
			}
		}
	}



	def buildAgent[DomainMessage <: DomainSignal]
	(agentDef: AgentTemplate[DomainMessage])
	(implicit clockRef: Clock.Ref, agentCreator: AgentCreator, simController: SimulationController.Ref): Ref =
		agentCreator.spawn(new Wrapper[DomainMessage](agentDef.name, clockRef, simController, agentDef.booter).init, agentDef.name)

}

trait AgentTemplate[DomainMessage <: DomainSignal] extends Identification {
	import AgentTemplate._

	lazy val self: SimRef = _self
	private var _self: SimRef = null
	def installSelf(s: SimRef) = _self = s
	val name: String
	private var _manager: SimRef = _
	protected lazy val manager: SimRef = _manager
	def installManager(m: SimRef) = _manager = m

	type HOST <: AgentTemplate[DomainMessage]
	type REQUEST <: DomainMessage
	type RESPONSE <: DomainSignal
 	type COMMAND <: DomainMessage
	type NOTIFICATION <: DomainSignal

	type CTX = AgentTemplate.SignallingContext[DomainMessage]
	type RUNNER = AgentTemplate.DomainRun[DomainMessage]

	def booter: AgentTemplate.DomainConfigure[DomainMessage]

}
