/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.{Actor, ActorLogging}
import com.saldubatech.ddes.Gateway.Configure

object SimActor {
  type Configuring = PartialFunction[Any,Unit]
}


abstract class SimActor(val name: String, implicit val gw: Gateway)
  extends Actor
    with SimActorMixIn
    with ActorLogging {
  import Epoch._
  import SimActor._

  def configure: Configuring

  private var configured = false

  def configuring: Receive = {
    case c: Configure =>
      log.debug(s"Configuring $name with ${c.config}")
      configured = true
      for(m <- c.config)
        configure.apply(m)
      gw.completedConfiguration(self)
      /*configure.andThen[Unit]{
        _ => gw.completedConfiguration(self)
      }.apply(c.config)*/
  }
  def running: Receive = {
    case act: Action =>
      log.debug(s"Processing Action: ${act.msg}")
      gw.receiveAction(act)
      process(act.from, act.targetTick).applyOrElse(act.msg,
          (msg: Any) ⇒ {
            log.error(s"Unknown Action Received $msg from ${act.from.path.name}")
            new Object
          })
      gw.completeAction(act)
    case msg: Any =>
      throw new IllegalArgumentException(s"Unknown Message Received $msg")
  }

  override def receive: Receive = {
    case a: Any =>
      if (configured) running(a)
      else configuring(a)
  }
}
