/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.ddes

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import com.saldubatech.base.Identification
import com.saldubatech.v1.ddes.Epoch.{Action, ActionRequest}


object SimActor {
  type Processing = PartialFunction[Any,Unit]
  val nullProcessing:Processing = Map.empty
  type ProcessingBuilder = (ActorRef, Long) => Processing
}

trait SimActor
extends Identification {

  protected implicit val gw: Gateway
  protected implicit val implicitSelf: SimActor = this

  // Shadowed from akka.Actor to avoid extension.
  val self: ActorRef
  def log: LoggingAdapter


  def tellTo(to: ActorRef, msg:Any, now: Long, delay: Long = 0): Unit = {
    if(delay > 0) gw.enqueue(ActionRequest(self, to, msg, delay))
    else {
      val act: Action =  Action(self, to, msg, now+delay)
      gw.actNow(act)
      to ! act
    }
  }
}
