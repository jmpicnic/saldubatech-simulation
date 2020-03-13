/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.saldubatech.ddes.Epoch._

import scala.collection.mutable


object GlobalClock {

  class Envelope(val actionable: Actionable) {
    def act(clock: ActorRef, from: ActorRef, to: ActorRef): Envelope = {
      clock ! this
      this match {
        case env: ActNow =>
          clock ! env
          to.tell(env.act, from)
        case env: Enqueue => clock ! env
      }
      this
    }
  }
  final case class Enqueue(act: ActionRequest) extends Envelope(act)
  final case class ActNow(act: Action) extends Envelope(act)
  //final case class StartActionOnSend(act: ActionRequest) extends Envelope(act)
  final case class StartActionOnReceive(act: Action) extends Envelope(act)
  final case class CompleteAction(act: Action)
  final case class Advance()
  // O&M Messages
  final case class RegisterTimeMonitor(observer: ActorRef)
  final case class Registered(observer: ActorRef, nObs: Int)
  final case class Deregistered(nObs: Int)
  final case class DeregisterTimeMonitor(observer: ActorRef)
  final case class StartTime(at: Option[Long] = None)
  final case class StopTime()
  final case class WhatTimeIsIt()
  // O&M Notifications Sent
  final case class ClockShuttingDown(at: Long)
  final case class NotifyAdvance(now: Long, nextTime: Long)
  final case class NoMoreWork(now: Long)



  def props(eventCollector: Option[ActorRef]): Props = Props(new GlobalClock(eventCollector))
}

class GlobalClock(eventCollector: Option[ActorRef], initialTick: Long = 0L) extends Actor with ActorLogging {
  import GlobalClock._

  override def receive: Receive = {
    case WhatTimeIsIt() => sender() ! epoch.now
    case RegisterTimeMonitor(obs) =>
      timeMonitors += obs
      obs ! Registered(obs, timeMonitors.size)
      log.debug(s"Registered Time Observer. Current observers: $timeMonitors")
    case DeregisterTimeMonitor(obs) =>
      timeMonitors -= obs
      obs ! Deregistered(timeMonitors.size)
    case StartTime(at) =>
      at foreach (t => epoch.reset(t))
      active = true
      maybeAdvance
    case StopTime() =>
      active = false
      if(!(epoch isActive) && (futureActions isEmpty)) notifyObservers(ClockShuttingDown(epoch.now))
    case Enqueue(act) =>
      if (active) {
        if (log.isDebugEnabled) tracer.lastReceived = Enqueue(act).toString
        //assert(act.delay > 0)
        if (act.delay > 0) {
          val targetTick = epoch.now + act.delay
          log.debug(s"GlobalClock now: ${epoch.now} : Enqueuing ${act.msg} for $targetTick")
          enqueueAction(Action(act.from, act.to, act.msg, targetTick))
        } else { // It is equivalent to ActNow, but needs to actually send the message to the target
          val action = Action(act.from, act.to, act.msg, epoch.now + act.delay)
          startOnSending(action)
          act.to ! action
        }
      } else {
          log.debug(s"GlobalClock now: ${epoch.now} : Enqueuing ${act.msg} at ${act.delay} while stopped")
          enqueueAction(Action(act.from, act.to, act.msg, act.delay))
      }
    case ActNow(action) =>
      if (log.isDebugEnabled) tracer.lastReceived = ActNow(action).toString
      assert(action.targetTick == epoch.now, s"Action target tick (${action.targetTick}) in wrong epoch (${epoch.now}): $action")
      startOnSending(action)
    case StartActionOnReceive(act) =>
      if (act.targetTick != epoch.now) {
        log.error(s"StartOnReceive: TargetTick (${act.targetTick}) should be for current epoch (${epoch.now}) on Act: $act")
        if (log.isDebugEnabled) tracer.dumpEpochHistory
        if (log.isDebugEnabled) tracer.dumpEpochCurrent
      }
      if (log.isDebugEnabled) tracer.lastReceived = StartActionOnReceive(act).toString
      assert(act.targetTick == epoch.now, s"StartOnReceive: TargetTick (${act.targetTick}) should be for current epoch (${epoch.now}) on Act: $act")
      startOnReceiving(act)
    case CompleteAction(act) =>
      if (log.isDebugEnabled) tracer.lastReceived = CompleteAction(act).toString
      complete(act)
    case a: Any =>
      log.error(s"Unknown message:$a ")
  }

  private val epoch: Epoch = Epoch(0)
  private var lastReport: Long = -1L
  private val futureActions = mutable.SortedMap.empty[Long,mutable.Queue[Action]]

  private def notifyObservers(msg: Any): Unit = {
    timeMonitors.foreach[Unit](_ ! msg)
  }
  private var active = false
  private val timeMonitors: mutable.Set[ActorRef] = mutable.Set()

  protected def startOnReceiving(act: Action): Unit = {
    log.debug(s"GlobalClock now: ${epoch.now} : Registering StartReceive ${act.msg}")
    if(log.isDebugEnabled) tracer.onReceive += act
    epoch startActionReceiving act
  }
  protected def startOnSending(act: Action): Unit = {
    log.debug(s"GlobalClock now: ${epoch.now} : Registering StartSend ${act.msg}")
    if(log.isDebugEnabled) tracer.onSend += act
    epoch startActionSending act
  }

  protected def complete(act: Action): Unit = {
    log.debug(s"GlobalClock now: ${epoch.now} : Registering Complete ${act.msg}")
    if(log.isDebugEnabled) tracer.onComplete += act
    epoch completeAction act
    maybeAdvance
  }

  protected def maybeAdvance(): Unit = {
    //assert(!epoch.isActive, s"Tried to advance while epoch is active at ${epoch.now}, Receiving: ${epoch.actionsReceiving}, Sending: ${epoch.actionsSending}")
    if(!epoch.isActive) {
      if (active) {
        log.debug(s"Ready to Advance at ${epoch.now} with future epochs: ${futureActions.size}")
        if (futureActions nonEmpty) {
          val (next_time, actions) = futureActions.head
          if (eventCollector isDefined) eventCollector.get ! NotifyAdvance(epoch.now, next_time)
          val shouldLog = if(log.isDebugEnabled) true else epoch.now - lastReport > 1e6.toLong
          if(shouldLog) {
            lastReport = epoch.now
            log.info(s"Advancing clock from: ${epoch.now} to: $next_time")
          }
          futureActions.remove(next_time)
          notifyObservers(NotifyAdvance(epoch.now, next_time))
          epoch.reset(next_time)
          if(log.isDebugEnabled) tracer.cycle(epoch.now)
          while (actions nonEmpty) {
            val act: Action = actions.dequeue
            if(log.isDebugEnabled) tracer.onSend += act
            startOnSending(act)
            act.to forward act
          }
        } else {
          //if (epoch.now > 0) self ! PoisonPill
          log.warning(s"NO MORE WORK TO DO at ${epoch.now}")
          if (eventCollector isDefined) eventCollector.get ! NoMoreWork(epoch.now)
          notifyObservers(NoMoreWork(epoch.now))
        }
      } else {
        //log.debug("Not advancing as there is still more work in current epoch")
        log.info(s"Notify Shutting down at ${epoch.now}")
        notifyObservers(ClockShuttingDown(epoch.now))
      }
    }
  }


  private def enqueueAction(action: Action): Unit = {
    if (futureActions.contains(action.targetTick))
      futureActions(action.targetTick) enqueue action
    else {
      val q = mutable.Queue[Action]()
      q enqueue action
      futureActions += (action.targetTick -> q)
    }
  }

  private object tracer {
    var lastReceived: String = ""
    var onSend: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var onReceive: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var onComplete: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var onSendLast: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var onReceiveLast: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var onCompleteLast: mutable.ListBuffer[Action] = mutable.ListBuffer[Action]()
    var last: Long = 0
    var current: Long = 0

    def cycle(now: Long): Unit = {
        onSendLast = onSend; onSend = mutable.ListBuffer()
        onReceiveLast = onReceive; onReceive = mutable.ListBuffer()
        onCompleteLast = onComplete; onComplete = mutable.ListBuffer()
        last = current; current = now
    }

    def dumpEpochHistory(): Unit = {
      log.error("============== LAST =========================")
      log.error(s"========== Last Epoch: $last ==========")
      log.error(s"=== Sent: ${onSendLast.size} = Receive: ${onReceiveLast.size} = Complete: ${onCompleteLast.size} ===")
      log.error("=============== On Send =====================")
      onSendLast.foreach(a => log.error(a.toString))
      log.error("=============== On Receive ==================")
      onReceiveLast.foreach(a => log.error(a.toString))
      log.error("=============== On Complete =================")
      onCompleteLast.foreach(a => log.error(a.toString))
      log.error("=============================================")
      log.error(s"===== Epoch.Sending: ${epoch.actionsSending.size}")
      epoch.actionsSending.foreach(a => log.error(a.toString))
      log.error(s"===== Epoch.Receiving ${epoch.actionsReceiving.size}")
      epoch.actionsReceiving.foreach(a => log.error(a.toString))
    }

    def dumpEpochCurrent(): Unit = {
      log.error("============== CURRENT ======================")
      log.error(s"=============== $current ===============")
      log.error(s"===== S: ${onSend.size} R: ${onReceive.size} C: ${onComplete.size} =======")
      log.error("=============== On Send =====================")
      onSend.foreach(a => log.error(a.toString))
      log.error("=============== On Receive ==================")
      onReceive.foreach(a => log.error(a.toString))
      log.error("=============== On Complete =================")
      onComplete.foreach(a => log.error(a.toString))
      log.error("=============================================")
      log.error(s"===== Epoch.S: ${epoch.actionsSending.size}")
      epoch.actionsSending.foreach(a => log.error(a.toString))
      log.error(s"===== Epoch.R ${epoch.actionsReceiving.size}")
      epoch.actionsReceiving.foreach(a => log.error(a.toString))
    }
  }

}

