/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, PSimSignal, SimRef}
import com.saldubatech.util.LogEnabled

object AgentTemplate {
	import com.saldubatech.ddes.Simulation.SimSignal
	type CTX = ActorContext[SimSignal]

	type AgentCreator = {def spawn[T](behavior: Behavior[T], name: String): ActorRef[T]}

	case class SourcedConfigure[SourceMessage <: DomainSignal, ConfigurationMessage <: DomainSignal]
	(override val from: ActorRef[PSimSignal[SourceMessage]], override val tick: Tick, payload: ConfigurationMessage) extends Identification.Impl with PSimSignal[ConfigurationMessage]

	case class SourcedRun[SourceMessage <: DomainSignal, TargetMessage <: DomainSignal]
	(override val from: ActorRef[PSimSignal[SourceMessage]], override val tick: Tick, override val payload: TargetMessage) extends Identification.Impl() with PSimSignal[TargetMessage]

	abstract class AgentNotification[DomainMessage <: DomainSignal] extends Identification.Impl() with ControllerMessage {
		val subject: SimRef[DomainMessage]
	}
	case class RegisterProcessor[DomainMessage <: DomainSignal](override val subject: SimRef[DomainMessage]) extends AgentNotification[DomainMessage]
	case class RegistrationConfigurationComplete[DomainMessage <: DomainSignal](override val subject: SimRef[DomainMessage]) extends AgentNotification[DomainMessage]

	trait SimpleSignallingContext[DomainMessage <: DomainSignal] extends LogEnabled {
		val now: Tick
		val aCtx: ActorContext[PSimSignal[DomainMessage]]
		implicit val clock: Clock.Ref
		protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): PSimSignal[TargetMsg]

		protected def doTell[TargetSignal <: DomainSignal, TargetMsg <: TargetSignal](to: SimRef[TargetSignal], msg: TargetMsg, delay: Option[Delay]): Unit =
			clock ! Clock.Enqueue(to, wrap(now + delay.getOrElse(0L), msg))

		def signaller[TargetMsg <: DomainSignal](to: SimRef[TargetMsg]): (TargetMsg, Option[Delay]) => Unit = (m, d) => doTell(to, m, d)
		def signal[TargetMsg <: DomainSignal, SG >: TargetMsg <: DomainSignal](to: SimRef[SG], msg: TargetMsg): Unit = doTell(to, msg, None)
		def signal[TargetMsg <: DomainSignal, SG >: TargetMsg <: DomainSignal](to: SimRef[SG], msg: TargetMsg, delay: Delay): Unit = doTell(to, msg, Some(delay))
		def signalSelf(msg: DomainMessage) = doTell(aCtx.self, msg, None)
		def signalSelf(msg: DomainMessage, delay: Delay) = doTell(aCtx.self, msg, Some(delay))
	}
	trait FullSignallingContext[DomainMessage <: DomainSignal, SourceSignal <: DomainSignal] extends SimpleSignallingContext[DomainMessage] {

		def from[FR >: SourceSignal <: DomainSignal]: SimRef[FR] = _from match {
			case lfr : SimRef[FR] => lfr
			case other => throw new RuntimeException(s"Unexpected Type of from: $other")
		}
		protected val _from: SimRef[SourceSignal]

		def reply[SG >: SourceSignal <: DomainSignal](msg: SG, delay: Delay) = doTell(from, msg, Some(delay))
		def reply[SG >: SourceSignal <: DomainSignal](msg: SG) = doTell(from, msg, None)

		def configureContext = ConfigureContext2(_from, now, aCtx)
		def commandContext = CommandContext2(_from, now, aCtx)
	}

	case class CommandContext2[HostSignal <: DomainSignal, SourceSignal <: DomainSignal](override protected val _from: ActorRef[PSimSignal[SourceSignal]], override val now: Tick, override val aCtx: ActorContext[PSimSignal[HostSignal]])
	                                                                                    (implicit override val clock: ActorRef[ClockMessage]) extends FullSignallingContext[HostSignal, SourceSignal] {

		override protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): PSimSignal[TargetMsg] = new SourcedRun[HostSignal, TargetMsg](aCtx.self, at, msg)
	}

	case class ConfigureContext2[HostSignal <: DomainSignal, SourceSignal <: DomainSignal](override protected val _from: ActorRef[PSimSignal[SourceSignal]], override val now: Tick, override val aCtx: ActorContext[PSimSignal[HostSignal]])
	                                                                                    (implicit override val clock: ActorRef[ClockMessage]) extends FullSignallingContext[HostSignal, SourceSignal] {

		override protected def wrap[TargetMsg <: DomainSignal](at: Tick, msg: TargetMsg): PSimSignal[TargetMsg] = new SourcedConfigure[HostSignal, TargetMsg](aCtx.self, at, msg)
	}

	trait DomainMessageProcessor[DomainMessage]
	object DomainMessageProcessor {
	}

	//trait DomainRun[DomainMessage] extends PartialFunction[DomainMessage, Function1[CommandContext[DomainMessage],DomainRun[DomainMessage]]]
	trait DomainRun[DomainMessage <: DomainSignal] extends Function[FullSignallingContext[DomainMessage, _ <: DomainSignal], PartialFunction[DomainMessage,DomainRun[DomainMessage]]] with DomainMessageProcessor[DomainMessage] {
		def orElse(other: DomainRun[DomainMessage]): DomainRun[DomainMessage] = (ctx:  FullSignallingContext[DomainMessage, _ <: DomainSignal]) => this(ctx) orElse other(ctx)

		def isDefinedAt(s: DomainMessage) = (ctx:  FullSignallingContext[DomainMessage, _ <: DomainSignal]) => this(ctx).isDefinedAt(s)
	}
	object DomainRun {
		def apply[DomainMessage <: DomainSignal](runLogic: PartialFunction[DomainMessage, DomainRun[DomainMessage]]): DomainRun[DomainMessage] = {
			implicit ctx:  FullSignallingContext[DomainMessage, _ <: DomainSignal] => runLogic
		}


		class Same[DomainMessage <: DomainSignal] extends DomainRun[DomainMessage] {
			override def apply(ctx:  FullSignallingContext[DomainMessage, _ <: DomainSignal]): PartialFunction[DomainMessage, DomainRun[DomainMessage]] = {
				case any =>
					throw new IllegalStateException(s"The 'Same' DomainRun should never be active: Received signal: $any from {ctx.from} as part of ${ctx.aCtx.self}")
					this
			}
		}
		def noOp[DomainMessage <: DomainSignal]: DomainRun[DomainMessage] = DomainRun[DomainMessage]{
			case n: Any if false => throw new RuntimeException("Should never be here")
		}

		def same[DomainMessage <: DomainSignal]: DomainRun[DomainMessage] = new Same[DomainMessage]

	}

	trait DomainConfigure[DomainMessage <: DomainSignal] extends DomainMessageProcessor[DomainMessage] {
		def configure(config: DomainMessage)(implicit ctx: FullSignallingContext[DomainMessage, _ <: DomainSignal]): DomainMessageProcessor[DomainMessage]
	}

	class Wrapper[DomainMessage <: DomainSignal](val name: String,
	                                             protected val clock: ActorRef[ClockMessage],
	                                             protected val controller: ActorRef[ControllerMessage],
	                                             protected val initialConfigurer: AgentTemplate.DomainConfigure[DomainMessage]
	                            ) extends LogEnabled {

		lazy val ref: SimRef[DomainMessage] = _ref.head
		private var _ref: Option[SimRef[DomainMessage]] = None

		def init: Behavior[PSimSignal[DomainMessage]] = Behaviors.setup[PSimSignal[DomainMessage]]{
			implicit ctx =>
				_ref = Some(ctx.self)
				controller ! RegisterProcessor[DomainMessage](ctx.self)
				doConfigure(initialConfigurer)
		}


		private def doConfigure(c: DomainConfigure[DomainMessage]): Behaviors.Receive[PSimSignal[DomainMessage]] = Behaviors.receive[PSimSignal[DomainMessage]]{
			(ctx, msg) => msg match {
				case cmd: SourcedConfigure[_, DomainMessage] =>
					ctx.log.debug(s"Configuring with $cmd")
					clock ! StartActionOnReceive(cmd)

					val next = c.configure(cmd.payload)(ConfigureContext2(cmd.from, cmd.tick, ctx)(clock))
					clock ! CompleteAction(cmd)
					next match {
						case configurer: DomainConfigure[DomainMessage] => doConfigure(configurer)
						case runner: DomainRun[DomainMessage] =>
							controller ! RegistrationConfigurationComplete(ref)
							behaviorizeRunner(runner)(clock)
					}

			}
		}
		private def behaviorizeRunner(runner: DomainRun[DomainMessage])(implicit clock: ActorRef[ClockMessage]): Behavior[PSimSignal[DomainMessage]] =
			Behaviors.receive[PSimSignal[DomainMessage]]{
				(ctx, msg) =>
					msg match {
						case cmd: SourcedRun[_, DomainMessage] => doProcessCommand(runner)(cmd)(clock, ctx)
						//case cmd: Run[DomainMessage] => doProcessCommand(runner)(cmd)(clock, ctx)
						//case Shutdown => do something
					}
			}

		private def doProcessCommand[FROM_SIGNAL <: DomainSignal](runner: DomainRun[DomainMessage])(cmd: SourcedRun[FROM_SIGNAL, DomainMessage])(implicit clock: ActorRef[ClockMessage], ctx: ActorContext[PSimSignal[DomainMessage]]): Behavior[PSimSignal[DomainMessage]] = {
			//ctx.log.info(s"Processing Command: ${cmd.payload} at ${cmd.tick}")
			clock ! StartActionOnReceive(cmd)
			val next: DomainRun[DomainMessage] = runner(CommandContext2(cmd.from, cmd.tick, ctx)(clock))(cmd.payload)
			clock ! CompleteAction(cmd)
			next match {
				case _ : DomainRun.Same[DomainMessage] => behaviorizeRunner(runner)
				case other => behaviorizeRunner(next)
			}
		}
	}

	def buildAgent[DomainMessage <: DomainSignal, SELF <: AgentTemplate[DomainMessage, SELF]]
	(agentDef: AgentTemplate[DomainMessage, SELF])
	(implicit clockRef: Clock.Ref, agentCreator: AgentCreator, simController: SimulationController.Ref): SimRef[DomainMessage] =
		agentCreator.spawn(new Wrapper[DomainMessage](agentDef.name, clockRef, simController, agentDef.booter).init, agentDef.name)

	trait AgentCompanion[DomainMessage <: DomainSignal] {
		type Ref = SimRef[DomainMessage]
		type SIGNAL = DomainMessage
		type CTX = AgentTemplate.FullSignallingContext[DomainMessage, _ <: DomainSignal]
		type RUNNER = AgentTemplate.DomainRun[DomainMessage]
	}

}

trait AgentTemplate[DomainMessage <: DomainSignal, SELF <: AgentTemplate[DomainMessage, SELF]] extends Identification {

	final lazy val self: SimRef[DomainMessage] = _self
	final private var _self: SimRef[DomainMessage] = _
	final def installSelf(s: SimRef[DomainMessage]) = _self = s
	val name: String
	private var _manager: SimRef[_ <: DomainSignal]= _
	final protected lazy val manager: SimRef[_ <: DomainSignal] = _manager
	final def installManager(m: SimRef[_ <: DomainSignal]) = _manager = m

	final type SIGNAL = DomainMessage
	final type HOST = SELF

	def booter: AgentTemplate.DomainConfigure[DomainMessage]

}
