/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.resource

import com.saldubatech.FSM.StateToken.StateChange
import com.saldubatech.FSM.{State, StateToken, TransitionToken}
import com.saldubatech.ddes.SimActorMixIn.Processing

object UseState {
  final case class Idle() extends StateToken("Idle")
  final case class InUse() extends StateToken("InUse")
  final case class Busy() extends StateToken("Busy")

  case class Acquire(quantity: Double) extends TransitionToken("Acquire")
  case class Release(quantity: Double) extends TransitionToken("Release")
}

class UseState(val capacity: Double) extends State(UseState.Idle()) {
  import UseState._
  import com.saldubatech.utils.Lang.ZERO_TOLERANCE

  var _usage: Double = 0.0
  val _current = new State(Idle())

  def isBusy: Boolean = current == Busy()
  def isIdle: Boolean = current == InUse()
  def isInUse: Boolean = current == InUse()
  def isLoaded: Boolean = !isIdle

  def usageProtocol: Processing = {
    case Acquire(q) =>
      acquire(q)
    case Release(q) =>
      release(q)
  }

  def acquire(quantity: Double): Option[StateChange] = {
    if ((_usage+quantity > capacity-ZERO_TOLERANCE) || (quantity < ZERO_TOLERANCE)) {
      // Over capacity, or q = 0 do nothing
      null
    } else if((capacity-_usage-quantity) > ZERO_TOLERANCE) {
      // Under capacity --> in Use
      _usage += quantity
      _current.update(InUse(), Acquire(quantity))
    } else {
      // full capacity --> Busy
      _usage = capacity
      _current.update(InUse(), Acquire(quantity))
    }
  }

  def release(quantity: Double): Option[StateChange] = {
    if((quantity > _usage + ZERO_TOLERANCE) || (quantity < ZERO_TOLERANCE)) {
      null // release more than in use --> Do nothing
    } else if (quantity < _usage - ZERO_TOLERANCE) {
      // partial release
      _usage -= quantity
      _current.update(InUse(), Release(quantity))
    } else {
      // complete release
      _usage = 0.0
      _current.update(InUse(), Release(quantity))
    }
  }

}
