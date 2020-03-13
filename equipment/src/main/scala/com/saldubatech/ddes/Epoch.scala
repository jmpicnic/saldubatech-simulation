/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.ActorRef

import scala.collection.mutable


object Epoch {
  def apply(initialTime: Long): Epoch = {new Epoch(initialTime)}

  class Actionable(val from: ActorRef, val to:ActorRef, val msg: Any, val time: Long)
  final case class Action(_from: ActorRef, _to:ActorRef, _msg: Any, targetTick: Long)
  extends Actionable(_from, _to, _msg, targetTick)
  final case class ActionRequest(_from: ActorRef, _to:ActorRef, _msg: Any, delay: Long)
  extends Actionable(_from, _to, _msg, delay)

}

class Epoch(initialTime: Long) {
  import Epoch._


  var now: Long = initialTime
  //private var done: Boolean = false

  //def isDone: Boolean = done

  val actionsReceiving: mutable.Set[Action] = mutable.Set[Action]()
  val actionsSending: mutable.Set[Action] = mutable.Set[Action]()

  def isActive: Boolean = (actionsReceiving nonEmpty) || (actionsSending nonEmpty)//actions nonEmpty

  def reset(atTime: Long): Unit = {
    now = atTime
    actions.clear// = mutable.Set[Action]()
    actionsReceiving.clear
    actionsSending.clear
//    done = false
  }

  def startActionSending(act: Action): Unit = {
    assert(act.targetTick >= now,s"SENDING: Starting action in past Epoch: Now: @$now Target${act.targetTick}, Action: $act")
//    if (done)
//      reset(act.target_tick)
    actionsSending += act
  }
  def startActionReceiving(act: Action): Unit = {
    assert(act.targetTick >= now,s"RECEIVING: Starting action in past Epoch: Now: @$now Target${act.targetTick}, Action: $act")
    //if (done)
    //  reset(act.target_tick)
    actionsReceiving += act
  }

  val actions: mutable.Set[Action] = mutable.Set[Action]()
  def startAction(act: Action): Unit = {
    assert(act.targetTick >= now,s"Starting action in past Epoch: Now: @$now Target${act.targetTick}, Action: $act")
//    if (done)
//      reset(act.target_tick)
    actions += act
  }

  def completeAction(act: Action): Unit = {
    var inSending = false
    assert(actionsReceiving contains act, s"Action sent Complete before StartOnReceive: $act")
    actionsReceiving remove act
    if (actionsSending contains act) {
      actionsSending remove act
      inSending = true
    }
    //assert(inReceiving || inSending, s"Tried to complete action before starting it: $act")
    //if(!inReceiving && !inSending)
    //  throw new IllegalStateException(s"Tried to complete action before starting it: $act")
  }


  def completeActionOld(act: Action): Unit = {
    assert(actions contains act, s"Tried to complete action before starting it: $act")
    if (actions contains act) {
      actions remove act
      if (actions isEmpty) {
        //done = true
      }
    }
    else
      throw new IllegalStateException(s"Tried to complete action before starting it: $act")
  }

}
