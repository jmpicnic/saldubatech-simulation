/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Material
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActorImpl, SimMessage}
import com.saldubatech.randomvariables.Distributions.LongRVar
import com.saldubatech.util.Lang._

object SimpleRandomExecution {
  def props(name: String, gw: Gateway, delayer: LongRVar): Props =
    Props(new SimpleRandomExecution(name, gw, delayer))

  // Configuration of owning Processor
  case class ConfigureOwner(newOwner: ActorRef)

  trait ExecutionCommand extends ProcessingCommand

  // Processor --> Executor
  final case class Process(commandId: String, material: Option[Material])
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
  // Executor --> Processor
  final case class CompleteStaging(commandId: String, job: Option[Material])
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
  final case class CompleteProcessing(commandId: String, material: Option[Material] = None, result: Option[Material] = None)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
}

class SimpleRandomExecution(name: String, gw: Gateway, delayer: LongRVar)
  extends SimActorImpl(name, gw) {

  import SimpleRandomExecution._

  private var owner: Option[ActorRef] = _

  override def process(from: ActorRef, at: Long): Processing = {
    case Process(commandId, material) =>
      log.debug(s"Execution Processing: $commandId, $material")
      //CompleteStaging(commandId, material) _to owner.! now at // instant ingestion into processing
      CompleteStaging(commandId, material) ~> owner.! now at // instant ingestion into processing
      val (delay, result) = doIt(commandId, at, material)
      //CompleteProcessing(commandId, material, result) _to owner.! in (at, delay)
      CompleteProcessing(commandId, material, result) ~> owner.! in (at, delay)
  }

  override  def configure: Configuring = {
    case ConfigureOwner(newOwner) => registerOwner(newOwner)
  }

  protected def registerOwner(_owner: ActorRef): Unit = owner = _owner.?

  protected def doIt(commandId: String, now: Long, material: Option[Material]): (Long, Option[Material]) = (delayer(), material)

}
