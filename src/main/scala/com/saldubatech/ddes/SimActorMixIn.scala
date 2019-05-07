/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.saldubatech.ddes.Epoch.{Action, ActionRequest}


object SimActorMixIn {
  type Processing = PartialFunction[Any,Unit]
  val nullProcessing:Processing = Map.empty
}

trait SimActorMixIn extends Actor with ActorLogging {
  import SimActorMixIn._

  protected implicit val gw: Gateway
  protected implicit val implicitSelf: SimActorMixIn = this


  def tellTo(to: ActorRef, msg:Any, now: Long, delay: Long = 0): Unit = {
    if(delay > 0) gw.enqueue(ActionRequest(self, to, msg, delay))
    else {
      val act: Action =  Action(self, to, msg, now+delay)
      gw.actNow(act)
      to ! act
    }
  }
  def process(from: ActorRef, at: Long): Processing
}
