/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.base.{Identification, Monitored}
import com.saldubatech.ddes.Processor.{ActionCommand, ProcessCommand, ProcessorMessage}
import com.saldubatech.ddes.Simulation.{ControllerMessage, Notification, Signal, SimSignal}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable
/**
@startuml
actor simController
boundary sender
participant clock
boundary receiver
 == Simulation Message ==
 sender -> receiver: [time = at] domainMessage
 == Implementation: Sending a Message ==
 sender -> clock: Enqueue(receiver, Action(from, at, domainMessage))
 alt at == now
 clock -> receiver: Action(from, at, domainMessage)
 receiver -> clock: StartOnReceiver(action)
 hnote over receiver
 do something
 end hnote
 receiver -> clock: EndOnReceiver(action)
 clock -> clock: maybeAdvance
 else at > now
 hnote over clock
 enqueue for later
 end hnote
 clock -> clock: maybeAdvance
 else at < now
 clock -> clock: Throw Exception
 end
 == After Maybe Advance ==
 alt morework == true
 loop now = nextTime;\nforeach(action => action.at == now)
 clock -> receiver: action
 receiver -> clock: start
 hnote over receiver
  Do something
  with action
 end hnote
 end
 else moreWork == false
 hnote over clock
 Done
 end hnote
 end
 == After All work is DONE ==
 clock -> simController: NoMoreWork
 simController -> clock: EndSimulation
@enduml
 */
object Clock {
	type Tick = Long
	type Delay = Long
	trait ClockMessage
	type Ref = ActorRef[ClockMessage]

	sealed trait ClockCommand extends Signal with ClockMessage

	sealed trait ClockAction[ACTION <: SimSignal] extends ClockCommand
	case class Enqueue[ACTION <: SimSignal](to: ActorRef[ACTION], act: ACTION) extends Identification.Impl() with ClockAction[ACTION]
	//case class ActNow(act: Action) extends ClockAction

	sealed trait ClockTimeKeeping extends ClockCommand
	case class StartActionOnReceive(act: SimSignal) extends Identification.Impl() with ClockTimeKeeping
	case class CompleteAction(act: SimSignal) extends Identification.Impl() with ClockTimeKeeping
	case object Advance extends Identification.Impl() with ClockTimeKeeping

	sealed trait ClockManagementMessage extends ClockCommand
	sealed trait ClockControlMessage extends ClockManagementMessage
	case class StartTime(at: Tick = 0L) extends Identification.Impl() with ClockManagementMessage
	case object StopTime extends Identification.Impl() with ClockManagementMessage

	sealed trait ClockMonitoringMessage extends ClockManagementMessage
	case class WhatTimeIsIt(from: ActorRef[TheTimeIs]) extends Identification.Impl() with ClockMonitoringMessage
	case class RegisterMonitor[M >: ClockNotification](monitor: ActorRef[M]) extends Identification.Impl() with ClockMonitoringMessage
	case class DeregisterMonitor[M >: ClockNotification](monitor: ActorRef[M]) extends Identification.Impl() with ClockMonitoringMessage

	sealed trait ClockNotification extends ControllerMessage
	case class RegisteredClockMonitors(n: Int) extends Identification.Impl() with ClockNotification with ControllerMessage

	sealed trait ClockStateNotification extends ClockNotification with ControllerMessage
	case class StartedOn(tick: Tick) extends Identification.Impl() with ClockNotification
	case class NotifyAdvance(from: Tick, to: Tick) extends Identification.Impl() with ClockStateNotification
	case class NoMoreWork(tick: Tick) extends Identification.Impl() with ClockStateNotification
	case class ClockShuttingDown(tick: Tick) extends Identification.Impl() with ClockStateNotification
	case class TheTimeIs(tick: Tick) extends Identification.Impl() with ClockNotification

	def apply() = new Clock().init

}

class Clock[ACTION <: SimSignal] private() extends Monitored[Clock.ClockNotification, Clock.RegisteredClockMonitors]
	with LogEnabled {
	import Clock._

	var current: Option[Clock[ACTION]] = None

	val starter: PartialFunction[ClockMessage, Behavior[ClockMessage]] = {
		case startMsg: StartTime =>
			start(startMsg.at)
			run
		case qAct: Enqueue[ACTION] =>
			enqueue(qAct.act.tick, qAct)
			Behaviors.same
	}
	val monitoring: PartialFunction[ClockMessage, Behavior[ClockMessage]] = {
		case WhatTimeIsIt(sender) => sender ! TheTimeIs(now); Behaviors.same
		case RegisterMonitor(monitor) => add(monitor); Behaviors.same
		case DeregisterMonitor(monitor) => remove(monitor); Behaviors.same
	}
	val timeKeeper:  PartialFunction[ClockMessage, Behavior[ClockMessage]] = {
		case Advance => maybeAdvance
		case CompleteAction(act) => closeAction(act)
		case StartActionOnReceive(act) => openAction(act); Behaviors.same[ClockMessage]
	}

	def init = {
		if(current.isDefined) throw new IllegalStateException(s"A Clock Actor has already been instantiated, cannot instantiate two global clocks")
		Behaviors.receive[ClockMessage] {(ctx, msg) => (starter orElse monitoring)(msg)}
	}

	private def run = Behaviors.receive[ClockMessage] {
		(ctx, msg) =>
			msg match {
				case mngCmd: ClockMonitoringMessage => monitoring(mngCmd)
				case tk: ClockTimeKeeping => timeKeeper(tk)
				case act: ClockAction[ACTION] => clkAct(ctx, act)
				case StopTime =>
					//if(!(epoch isActive) && (futureActions isEmpty)) notifyObservers(ClockShuttingDown(epoch.now))
					notifyObservers(ClockShuttingDown(now))
					Behaviors.stopped
			}
	}


	override protected lazy val subscriptionMessage: Set[ActorRef[ClockNotification]] => RegisteredClockMonitors =
		obs => RegisteredClockMonitors(obs.size)


	private def clkAct(ctx: ActorContext[ClockMessage], act: ClockAction[ACTION]): Behavior[ClockMessage] = {
		log.debug(s"Clock Action Received: $act at $now")
		act match {
			case qAct @ Enqueue(to, actCommand) =>
				if(actCommand.tick == now) sendNow(to, actCommand)
				else enqueue(actCommand.tick, qAct)
				maybeAdvance
		}
		Behaviors.same[ClockMessage]
	}

	private def start(at: Tick) = {
		log.debug(s"Start the Clock at $at")
		current = Some(this)
		now = at
		notifyObservers(StartedOn(now))
		maybeAdvance
	}

	private def sendNow(to: ActorRef[ACTION], cmd: ACTION): Unit = {
		openAction(cmd)
		cmd match {
			case pcmd: ProcessCommand[_] => log.info(s"MSC: ${cmd.from.path.name} -> ${to.path.name}: [$now] ${pcmd.dm}")
			case other => log.info(s"Sending Non Process Command: $other")
		}
		log.debug(s"Sending Now($now): To: $to($cmd)")
		to ! cmd
	}

	private def enqueue(tick: Tick, eq: Enqueue[ACTION]): Behavior[ClockMessage] = {
		log.debug(s"Clock: Enqueueing $eq for $tick")
		if(tick >= now) {
			actionQueue += tick -> (actionQueue.getOrElse(tick, mutable.ListBuffer.empty) += eq)
			Behaviors.same
		} else {
			log.error(s"Received action($eq) for earlier: $tick when now is $now")
			throw new IllegalStateException(s"Received action($eq) for earlier: $tick when now is $now")
			//Behaviors.stopped
		}
	}

	private def maybeAdvance: Behavior[ClockMessage] =
		if(isClosed || (!activatedEpoch && current.nonEmpty)) {
			if(actionQueue.nonEmpty) {
				val (tick, actionList) = actionQueue.head
				val past = now
				log.info(s"Advancing from $past to $tick")
				now = tick
				actionQueue -= tick
				activatedEpoch = false
				notifyObservers(NotifyAdvance(past, now))
				actionList.foreach(cmd => sendNow(cmd.to, cmd.act))
				Behaviors.same[ClockMessage]
			} else {
				log.debug(s"No more work at $now")
				notifyObservers(NoMoreWork(now))
				Behaviors.same[ClockMessage]
			}
		} else {
			log.debug(s"Epoch at $now is not closed || active: $activatedEpoch, openActions: ${openActions.size}")
			Behaviors.same[ClockMessage]
		}

	private def openAction(act: SimSignal): Unit = {
		log.debug(s"Open Action: $act")
		activatedEpoch = true
		openActions += act.uid
		Behaviors.same[ClockMessage]
	}

	private def closeAction(act: SimSignal): Behavior[ClockMessage] = {
		log.debug(s"Closing Action: $act")
		if(!openActions.contains(act.uid)) throw new IllegalArgumentException(s"Action Not Open: $act")
		openActions -= act.uid
		maybeAdvance
	}

	private def isClosed: Boolean = activatedEpoch && openActions.isEmpty
	private var activatedEpoch = false
	private val openActions = mutable.Set.empty[String]

	private var now: Tick = 0
	private val actionQueue: mutable.SortedMap[Tick, mutable.ListBuffer[Enqueue[ACTION]]] = mutable.SortedMap.empty

}
