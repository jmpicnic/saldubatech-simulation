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
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object SimActorImpl {
  type Configuring = PartialFunction[Any,Unit]
}


abstract class SimActorImpl(name: String, implicit val gw: Gateway)
  extends Actor
    with SimActor
    with ActorLogging {
  import Epoch._
  import SimActorImpl._
  override protected def givenId: Option[String] = name.?

  def configure: Configuring

  private var configured = false

  def process(from: ActorRef, at: Long): Processing


  def configuring: Receive = {
    case c: Configure =>
      log.debug(s"Configuring $uid with ${c.config}")
      configured = true
      for(m <- c.config)
        configure.apply(m)
      cascades.groupBy{
        case (to, msg) => to
      }.foreach{
        case (to, msgs) => to ! Configure(msgs.map(e => e._2): _*)
      }
      cascades.clear
      gw.completedConfiguration(self)
  }
  private val cascades: mutable.ListBuffer[(ActorRef, Any)] = mutable.ListBuffer.empty
  protected def cascadeConfiguration(to: ActorRef, msg: Any): Unit = {
    cascades += ((to, msg))
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
