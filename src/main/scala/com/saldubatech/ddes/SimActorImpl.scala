/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.saldubatech.ddes.Gateway.Configure
import com.saldubatech.ddes.SimActor.Processing

object SimActorImpl {
  type Configuring = PartialFunction[Any,Unit]
}


abstract class SimActorImpl(val name: String, implicit val gw: Gateway)
  extends Actor
    with SimActor
    with ActorLogging {
  import Epoch._
  import SimActorImpl._

  def configure: Configuring

  private var configured = false

  def process(from: ActorRef, at: Long): Processing


  def configuring: Receive = {
    case c: Configure =>
      log.debug(s"Configuring $name with ${c.config}")
      configured = true
      for(m <- c.config)
        configure.apply(m)
      gw.completedConfiguration(self)
  }
  def running: Receive = {
    case act: Action =>
      log.debug(s"Processing Action: ${act.msg}")
      gw.receiveAction(act)
      process(act.from, act.targetTick).applyOrElse(act.msg,
          (msg: Any) ⇒ {
            log.error(s"Unknown Action Received $msg from ${act.from.path.name} at ${act.targetTick}")
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
