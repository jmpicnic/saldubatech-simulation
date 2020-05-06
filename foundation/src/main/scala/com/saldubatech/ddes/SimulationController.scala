/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Props}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, ClockNotification, NoMoreWork, NotifyAdvance, RegisterMonitor, RegisteredClockMonitors, StartTime, StartedOn, StopTime, Tick}
import com.saldubatech.ddes.Processor.{CompleteConfiguration, ProcessorMessage, RegisterProcessor, Run}
import com.saldubatech.ddes.Simulation.{ControllerMessage, Notification, Signal}
import com.saldubatech.util.Lang._

import scala.collection.mutable
import scala.concurrent.Await

object SimulationController {

	type Ref = ActorRef[ControllerMessage]

	sealed trait ControllerCommand extends Signal with ControllerMessage
	case object SimulationShutdown extends Identification.Impl() with ControllerCommand
	case object CheckSimulationState extends Identification.Impl() with ControllerCommand
	case object KILL extends Identification.Impl() with ControllerCommand

	sealed trait ControllerNotification extends Signal
	case class SimulationState(stateInfo: TBD) extends Identification.Impl() with ControllerNotification


	val PB = PF[ControllerMessage, Behavior[ControllerMessage]] _

}

class SimulationController(simulationName: String, startTime: Tick, props: Props = Props.empty) {
	import SimulationController._

	private var _clkHolder: ActorRef[ClockMessage] = _

	private def initClock =
		if(_clkHolder == null) throw new IllegalStateException("Clock Not initialized yet")
		else _clkHolder

	//def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] =
	//	Await.result(internalSystem.ask(ActorTestKitGuardian.SpawnActorAnonymous(behavior, _, props)), timeout.duration)



	def start = Behaviors.setup[ControllerMessage]{
		ctx =>
			val wkw = ctx
			_clkHolder = ctx.spawn(Clock(), "GlobalClock", props)
			_clkHolder ! RegisterMonitor(ctx.self)
			initializing
	}

	def initializing= Behaviors.receive[ControllerMessage] {
		(ctx: ActorContext[ControllerMessage], msg: ControllerMessage) => (confTracker.configuring(run, startTime)
			orElse PB{
				case RegisteredClockMonitors(n) => Behaviors.same
			})(msg)
	}

	def run = Behaviors.receive[ControllerMessage]{
		(ctx: ActorContext[ControllerMessage], msg: ControllerMessage) => (runningClock orElse PB{
			case SimulationShutdown =>
				clock ! StopTime
				shuttingDown
		})(msg)
	}

	val runningClock= PB{
		case NoMoreWork(at) =>
			clock ! StopTime
			shuttingDown
		case StartedOn(at) => Behaviors.same
		case NotifyAdvance(from, to) => Behaviors.same
		case RegisteredClockMonitors(n) => Behaviors.same
	}

	def shuttingDown: Behavior[ControllerMessage] = Behaviors.receive[ControllerMessage]{
		(ctx, msg) => msg match {
			case NoMoreWork(at) =>
				clock ! StopTime
				Behaviors.stopped
			case other =>
				ctx.log.info(s"Received unexpected Message while ShuttingDown: $other")
				Behaviors.same
		}
	}
	private val system: ActorSystem[ControllerMessage] = ActorSystem(this.start, simulationName)
	private lazy val clock = initClock
	private lazy val confTracker = new ConfigurationTracker(clock)

	private class ConfigurationTracker(clock: ActorRef[ClockMessage]) {
		val registered = mutable.Set.empty[ActorRef[ProcessorMessage]]
		val pending = mutable.Set.empty[ActorRef[ProcessorMessage]]
		def isInitialized: Boolean = registered.nonEmpty && pending.isEmpty

		def doneRegistering(next: Behavior[ControllerMessage], at: Tick): Behavior[ControllerMessage]  = Behaviors.receive {
			(ctx, msg) => msg match {
				case CompleteConfiguration(processor) if pending contains processor =>
					pending -= processor
					Behaviors.same
				case Run(at) =>
					if (isInitialized) {
						clock ! StartTime(at)
						next
					} else Behaviors.same
				}
		}

		def configuring(next: Behavior[ControllerMessage], at: Tick): PartialFunction[ControllerMessage, Behavior[ControllerMessage]] = {
			case RegisterProcessor(processor) =>
				registered += processor
				pending += processor
				Behaviors.same
			case CompleteConfiguration(processor) if pending contains processor =>
				pending -= processor
				Behaviors.same
			case Run(at) => doneRegistering(next, at)
		}
	}

}
