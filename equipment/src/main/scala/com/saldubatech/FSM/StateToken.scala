/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.FSM

object StateToken {
  final case class StateValue(token: StateToken)
  final case class StateChange(from: StateToken, to: StateToken, transition: TransitionToken)
}

class StateToken(val name: String) {

}
