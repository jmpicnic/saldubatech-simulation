/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.saldubatech.base.Monitored
import com.saldubatech.ddes.Processor.{ActionCommand, ProcessorMessage}
import com.saldubatech.ddes.Simulation.{Command, Notification}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.util.Logging

import scala.collection.mutable

object Clock {
	type Tick = Long
	type Delay = Long
	trait ClockMessage
	type ClockRef = ActorRef[ClockMessage]

	sealed trait ClockCommand extends Command with ClockMessage

	sealed trait ClockAction extends ClockCommand
	case class Enqueue(to: ActorRef[ProcessorMessage], act: ActionCommand) extends ClockAction
	//case class ActNow(act: Action) extends ClockAction

	sealed trait ClockTimeKeeping extends ClockCommand
	case class StartActionOnReceive(act: ActionCommand) extends ClockTimeKeeping
	case class CompleteAction(act: ActionCommand) extends ClockTimeKeeping
	case object Advance extends ClockTimeKeeping

	sealed trait ClockManagementMessage extends ClockCommand
	sealed trait ClockControlMessage extends ClockManagementMessage
	case class StartTime(at: Tick = 0L) extends ClockManagementMessage
	case object StopTime extends ClockManagementMessage

	sealed trait ClockMonitoringMessage extends ClockManagementMessage
	case class WhatTimeIsIt(from: ActorRef[TheTimeIs]) extends ClockMonitoringMessage
	case class RegisterMonitor[M >: ClockNotification](monitor: ActorRef[M]) extends ClockMonitoringMessage
	case class DeregisterMonitor[M >: ClockNotification](monitor: ActorRef[M]) extends ClockMonitoringMessage

	sealed trait ClockNotification extends Notification with ControllerMessage
	case class RegisteredClockMonitors(n: Int) extends ClockNotification with ControllerMessage

	sealed trait ClockStateNotification extends ClockNotification with ControllerMessage
	case class StartedOn(at: Tick) extends ClockNotification
	case class NotifyAdvance(from: Tick, to: Tick) extends ClockStateNotification
	case class NoMoreWork(at: Tick) extends ClockStateNotification
	case class ClockShuttingDown(at: Tick) extends ClockStateNotification
	case class TheTimeIs(tick: Tick) extends ClockNotification

	def apply() = new Clock().init

}

class Clock private () extends Monitored[Clock.ClockNotification, Clock.RegisteredClockMonitors]
	with Logging {
	import Clock._

	var current: Option[Clock] = None

	val starter: PartialFunction[ClockMessage, Behavior[ClockMessage]] = {
		case startMsg: StartTime =>
			start(startMsg.at)
			run
		case qAct @ Enqueue(to, actCommand) =>
			enqueue(actCommand.at, qAct)
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
				case act: ClockAction => clkAct(ctx, act)
				case StopTime =>
					//if(!(epoch isActive) && (futureActions isEmpty)) notifyObservers(ClockShuttingDown(epoch.now))
					notifyObservers(ClockShuttingDown(now))
					Behaviors.stopped
			}
	}


	override protected lazy val subscriptionMessage: Set[ActorRef[ClockNotification]] => RegisteredClockMonitors =
		obs => RegisteredClockMonitors(obs.size)


	private def clkAct(ctx: ActorContext[ClockMessage], act: ClockAction): Behavior[ClockMessage] = {
		log.debug(s"Clock Action Received: $act at $now")
		act match {
			case qAct @ Enqueue(to, actCommand) =>
				if(actCommand.at == now) sendNow(to, actCommand)
				else enqueue(actCommand.at, qAct)
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
	private def sendNow(to: ActorRef[ProcessorMessage], cmd: ActionCommand): Unit = {
		openAction(cmd)
		log.debug(s"Sending Now($now): To: $to($cmd)")
		to ! cmd
	}
	private def enqueue(tick: Tick, act: Enqueue): Behavior[ClockMessage] = {
		log.debug(s"Clock: Enqueueing $act for $tick")
		if(tick >= now) {
			actionQueue += tick -> (actionQueue.getOrElse(tick, mutable.ListBuffer.empty) += act)
			Behaviors.same
		} else {
			log.error(s"Received action($act) for earlier: $tick when now is $now")
			Behaviors.stopped
		}
	}

	private def maybeAdvance: Behavior[ClockMessage] =
		if(isClosed || (!activatedEpoch && current.nonEmpty)) {
			if(actionQueue.nonEmpty) {
				val (tick, actionList) = actionQueue.head
				val past = now
				log.debug(s"Advancing from $past to $tick")
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

	private def openAction(act: ActionCommand): Unit = {
		log.debug(s"Open Action: $act")
		activatedEpoch = true
		openActions += act.uid
		Behaviors.same[ClockMessage]
	}

	private def closeAction(act: ActionCommand): Behavior[ClockMessage] = {
		log.debug(s"Closing Action: $act")
		if(!openActions.contains(act.uid)) throw new IllegalArgumentException(s"Action Not Open: $act")
		openActions -= act.uid
		maybeAdvance
	}

	private def isClosed: Boolean = activatedEpoch && openActions.isEmpty
	private var activatedEpoch = false
	private val openActions = mutable.Set.empty[String]

	private var now: Tick = 0
	private val actionQueue: mutable.SortedMap[Tick, mutable.ListBuffer[Enqueue]] = mutable.SortedMap.empty

}
