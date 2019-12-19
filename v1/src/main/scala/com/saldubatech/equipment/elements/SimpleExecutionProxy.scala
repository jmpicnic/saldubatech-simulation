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


import akka.actor.ActorRef
import com.saldubatech.base.Material
import com.saldubatech.base.resource.DiscreteResourceBox
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.util.Lang._

import scala.collection.mutable

object SimpleExecutionProxy {
  def apply(name: String, capacity: Int,
            host: ExecutionProxy.ExecutionObserver with SimActor,
            executor: ActorRef): SimpleExecutionProxy = {
    new SimpleExecutionProxy(name,
      Set[String]() ++ (1 to capacity).map(_ => java.util.UUID.randomUUID().toString),
      host, executor
    )
  }
}


class SimpleExecutionProxy(name: String, assets: Set[String],
                           implicit val executionHost: ExecutionProxy.ExecutionObserver with SimActor,
                           executor: ActorRef)
  extends DiscreteResourceBox(name, assets)
	  with ExecutionProxy[ProcessingCommand] {
  import SimpleRandomExecution._

  private val executorCommandsWip: mutable.Map[String, String] = mutable.Map[String, String]()

  override def executeJob(cmd: ProcessingCommand, at: Long, load: Option[Material] = None): Boolean = {
    val resource = checkoutOne()
    if(resource isDefined) {
      executorCommandsWip += (cmd.uid -> resource.!)
      executionHost.log.debug(s"$name Command Execution for: $cmd (${if(load isDefined) load.!.uid else "No Material"}")
      //executionHost send Process(cmd.uid, load) _to executor now at;
      Process(cmd.uid, load) ~> executor now at
      true
    } else {
      false
    }
  }

  def releaseJob(commandId: String, at: Long): Unit = {
    assert(executorCommandsWip contains commandId, s"Command $commandId is not pending in Executor $name at: $at")
    checkin(executorCommandsWip(commandId))
    executorCommandsWip -= commandId
  }

  def executorResponding(from: ActorRef, tick: Long): Processing = {
    case CompleteProcessing(commandId, load, result) if from == executor && (executorCommandsWip contains commandId) =>
      executionHost.log.debug(s"$name Completed Execution with: ${load.!.uid} at: $tick")
      executionHost.onCompleteCommand(commandId, load, result, tick)
    case CompleteStaging(commandId, load) if from == executor && (executorCommandsWip contains commandId) =>
      executionHost.log.debug(s"$name Completed Staging with: ${load.!.uid} at $tick")
      executionHost.onCompleteStaging(commandId, load, tick)
  }

}
