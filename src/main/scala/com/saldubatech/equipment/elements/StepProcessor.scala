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
import com.saldubatech.base.{AbstractChannel, Material}
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimMessage
import com.saldubatech.events.OperationalEvent
import com.saldubatech.utils.Boxer._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

object StepProcessor {
    // Messages to Processor to register/undregister the Executor
  case class Register(registree: ActorRef)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
  case class Deregister(registree: ActorRef)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)

  trait Induct {
    def consumeInput(load: Material, at: Long): Unit
  }

  val fifoSelector: JobSelectionPolicy= new JobSelectionPolicy {
		override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] =
			queue.toList
	}

  trait JobSelectionPolicy {
    def prioritizeJobs(queue: mutable.ListBuffer[Material]): List[Material]
  }

  trait DeliveryPolicy {
    def prioritize(finishedGoods:  List[(String, Material)]): mutable.Queue[(String, Material)]
  }


}


trait StepProcessor
    extends EquipmentActorMixIn
      with Induct.Processor
      with Discharge.Processor
      with ExecutionProxy.ExecutionObserver {
  import StepProcessor._

  val p_capacity: Int
  val p_executor: ActorRef
  val p_jobSelectionPolicy: JobSelectionPolicy
  val p_deliveryPolicy: DeliveryPolicy
  val name: String

  protected def induct: StepProcessor.Induct
  protected def discharge: Discharge

  private val ep: SimpleExecutionProxy = SimpleExecutionProxy(name+"executor",p_capacity,this,p_executor)
  private val jobSelectionPolicy: JobSelectionPolicy = p_jobSelectionPolicy
  private val deliveryPolicy: DeliveryPolicy = p_deliveryPolicy


  private val processorQueue = mutable.ListBuffer[Material]()
  private val finishedGoods: mutable.ListBuffer[(String, Material)] = mutable.ListBuffer[(String, Material)]()

  def dischargeProcessor: Discharge.Processor = this
  val inductProcessor: Induct.Processor = this

  protected def processing(from: ActorRef, at: Long): Processing = ep.executorResponding(from, at)

  // From Upstream Induct
  override def newJobArrival(load: Material, at: Long): Unit = {
    log.debug(s"New Job Arrival at StepProcessor: $load")
    processorQueue += load
    attemptProcessing(at)
  }

  // From Downstream Discharge
  def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit = {
    log.debug("Delivering because outbound available")
    attemptDelivery(at)
    attemptProcessing(at)
  }

  // From ExecutionProxy.Processor
  override def onCompleteCommand(commandId: String, load: Option[Material], result: Option[Material], at: Long): Unit = {
    addFinishedGoods(commandId, result.!)//finishedGoods.append((job, result))
    collect(at, OperationalEvent.Complete, name, load.!.uid)
    log.debug("Delivering because job complete")
    attemptDelivery(at)
    attemptProcessing(at)
  }

  override def onCompleteStaging(commandid: String, load: Option[Material], at: Long): Unit = {
    log.debug(s"Consuming input from StepProcessor: $name onComplete Staging: $load at: $at")
    induct.consumeInput(load.!, at)
    collect(at, OperationalEvent.Start, name, load.!.uid)
  }

  protected def attemptProcessing(at: Long): Unit = {
    val prioritizedQueue: List[Material] = jobSelectionPolicy.prioritizeJobs(processorQueue)
    processAll(prioritizedQueue, at)
  }

  private def processAll(queue: List[Material], at: Long): Unit = {
    log.debug(s"Process All with $queue")
    val nextLoad = queue.headOption
    if(nextLoad.isDefined && ep.executeJob(ProcessingCommand(nextLoad.!.uid), at, nextLoad)) {
      processorQueue -= nextLoad.get
      processAll(queue.tail, at)
    }
  }

  private def attemptDelivery(at: Long): Unit = {
    deliverAll(deliveryPolicy.prioritize(finishedGoods.toList), at)
  }


  private def deliverAll(prioritizedDelivery: mutable.Queue[(String, Material)], at: Long): Unit = {
    if(prioritizedDelivery nonEmpty) {
      val fg = prioritizedDelivery.dequeue
      log.debug(s"In DeliverAll: Candidate to deliver at:$at, delivering ${fg._2.uid}")
      if (discharge.deliver(fg._2, at)) {
        log.debug(s"In DeliverAll Actual delivery at:$at, delivering ${fg._2.uid} part of command: $fg._1")
        removeFromFinishedGoods(fg)
        log.debug(s"Left to deliver: $prioritizedDelivery")
        ep.releaseJob(fg._1, at)
        //log.info(s"After releasing the job in the ExecProxy of $name")
        deliverAll(prioritizedDelivery, at)
        //log.info(s"After recursive call to deliverAll of $name")
      }
    }
  }

  private def addFinishedGoods(commandId: String, result: Material): Unit = {
    log.debug(s"Adding to finished goods(${finishedGoods.size}), command: $commandId, result: ${result.uid}")
    val t = (commandId, result)
    finishedGoods += t
  }

  private def removeFromFinishedGoods(t: (String, Material)): Unit = {
    log.debug(s"Removing from finished goods(${finishedGoods.size}), command:${t._1} result:${t._2.uid}")
    finishedGoods -= t
    log.debug(s"Removed from finished goods(${finishedGoods.size}), command:${t._1} result:${t._2.uid}")
  }


}
