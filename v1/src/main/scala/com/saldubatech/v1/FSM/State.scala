/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.FSM

class State(initial: StateToken) {
  import StateToken._
  private var _current = initial
  private var _lastTransition: TransitionToken = _

  def update(newState: StateToken, through: TransitionToken): Option[StateChange] = {
    _current = newState
    _lastTransition = through
    Option(StateChange(_current, newState, through))
  }

  def current(): StateToken = _current
  def lastTransition(): TransitionToken = _lastTransition

}
