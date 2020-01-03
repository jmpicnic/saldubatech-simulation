/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.spec

import akka.actor.ActorRef
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.ddes.SimActor
import com.saldubatech.v1.ddes.SimDSL._
import com.saldubatech.spec.ProcessorProtocol.Task

import scala.collection.mutable

/** The Processing Node in the network
	*
	* Decided Not to Include:
	*  - Batching on oubound delivery: As soon as a task ends, it is expected to be delivered.
	*
 */
object Node {
	import NodeProtocol._

	trait Fulfillment {
		def receiveDelivery(delivery: FulfillmentDelivery, via: DirectedChannel[Material]): Unit
	}

	trait Demand {
		def receiveDemand(request: DemandRequest, requester: ActorRef)(implicit at: Long): Unit
	}
}

object ImplementationAspects {
	import NodeProtocol._
	import ProcessorProtocol._

	trait ActiveTasks {
		protected val processor: Processor

		def deliverToInProcess(delivery: FulfillmentDelivery): Unit = {

		}
	}
}

trait Node extends SimActor
	with Node.Demand
	with Node.Fulfillment
	with ProcessorProtocol.ProcessorManager {

	import NodeProtocol._

	private val requests: mutable.ArrayBuffer[DemandRequest] = mutable.ArrayBuffer.empty[DemandRequest]

	private val availableMaterials:
		mutable.ListBuffer[(FulfillmentDelivery, DirectedChannel[Material])] = mutable.ListBuffer.empty

	private val finishedTasks: mutable.ListBuffer[(Task, Material)] = mutable.ListBuffer.empty

	override def receiveDemand(request: DemandRequest, requester: ActorRef)(implicit at: Long): Unit = {
		if(isAcceptable(request)) {
			requests += request
			doActivityCycle
		} else DemandDecline(request) ~> requester now at
	}

	override def receiveDelivery(delivery: FulfillmentDelivery, via: DirectedChannel[Material]): Unit = {
		if(deliverToInProcess(delivery)) {}
		else if (matchWithReadyTasks(delivery)) {}
		else availableMaterials += delivery -> via
		doActivityCycle
	}

	override def completedTask(task: Task, result: Material): Unit = {
		finishedTasks += task -> result
		doActivityCycle
	}


	protected def isAcceptable(request: DemandRequest): Boolean
	protected def deliverToInProcess(delivery: FulfillmentDelivery): Boolean
	protected def matchWithReadyTasks(delivery: FulfillmentDelivery): Boolean

	protected def doActivityCycle(): Unit = {
		deliveryFinishedProduct
		activateReadyTasks
		readyAcceptedRequests
		acceptPendingRequests
	}

	private def deliveryFinishedProduct(): Unit = {
		/*finishedTasks.foreach {
				case (task, material) => if (task.targetChannel.)
		}*/
	}
	private def activateReadyTasks(): Unit = {}
	private def readyAcceptedRequests(): Unit = {}
	private def acceptPendingRequests(): Unit = {}

}
