/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v0

import akka.actor.ActorRef
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Processor.ExecutionObserver
import com.saldubatech.base.processor.Task.ExecutionCommand
import com.saldubatech.base.{Identification, Material}
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActorImpl}

import scala.collection.mutable

object Node {
	type CommandId = String
	type DemandId = String
	type ToteId = String

	/**
		* A Specification of materials to be delivered.
		*/
	trait Job extends Identification


	/**
		* A specification of a delivery to fulfill a job (e.g., it may include delivery windows, batching requirements, ...)
		* @param job
		* @param id
		* @tparam J
		*/
	case class Demand[+J <: Job](job: J, id: String = java.util.UUID.randomUUID.toString) extends Identification.Impl(id)

	/**
		* Infomrmation associated to a node not accepting a Demand for a job.
		* @param demandId
		*/
	case class RejectDemand(demandId: DemandId) extends Identification.Impl

	/**
		* A request to accept a demand.
		*
		* @param demand
		* @param origin
		* @param arrivalTime
		* @tparam J
		* @tparam D
		*/
	case class Request[+J <: Job, D <: Demand[J]](demand: D, origin: ActorRef, arrivalTime: Long)


	// Fulfillment Layer
	case class Delivery[J <: Job, D <: Demand[J], M <: Material]()



	case class ProcessingNodeCycle(id: String)

}

abstract class Node[M <: Material, +J <: Node.Job, D <: Node.Demand[J], R <: Node.Request[J, D]]
(id: String, wipLimit: Int)
(implicit gw: Gateway)
	extends SimActorImpl(id, gw)
		with ExecutionObserver[M] {
	import Node._

	protected val jobRequests: mutable.Map[DemandId, R] = mutable.Map.empty
	protected val acceptedRequests: mutable.Map[DemandId, R] = mutable.Map.empty
	protected val readyTasks: mutable.Map[R, ExecutionCommand] = mutable.Map.empty
	protected val activeTasks: mutable.Map[CommandId, R] = mutable.Map.empty
	protected def wip: Int = activeTasks.size+readyTasks.size+acceptedRequests.size

	private def update(id: String)(implicit at: Long): Unit = ProcessingNodeCycle(id) ~> self now at

	protected def Updater(from: ActorRef, at: Long): Processing = {
		case ProcessingNodeCycle(msg) =>
			// Finalized delivered commands/tasks
			// check demand -> accept

		// assign materials to active, ready or accepted
		// check accept -> ready
		// check & prioritize ready -> active: Assign resources and activate
	}


	def maybeReceiveDemand(from: ActorRef, demand: D)(implicit at: Long): Boolean = {
		if (isAcceptable(demand)) {
			jobRequests += demand.uid -> requestForDemand(demand, from ,at)
			update("from demand")
			true
		} else {
			RejectDemand(demand.uid) ~> from now at
			false
		}
	}

	protected def doAcceptRequest(demandId: DemandId): Option[R] =
		jobRequests.get(demandId).map{ r =>
			jobRequests -= demandId
			acceptedRequests += demandId -> r
			r
		}

	protected def doReadyRequest(demand: DemandId): Option[R] =
		acceptedRequests.get(demand).map {r =>
			acceptedRequests -= demand
			val c = commandForRequest(r)
			readyTasks += r -> c
			r
		}

	protected def doActivateRequest(request: R): Option[R] =
		readyTasks.get(request).map{c =>
			readyTasks -= request
			activeTasks += c.uid -> request
			request
		}

	protected def doFinalizeTask(command: CommandId): Unit = activeTasks -= command


	// Implementor
	def requestForDemand(demand: D, from: ActorRef, at: Long): R
	def isAcceptable(demand: D): Boolean
	def commandForRequest(request: R): ExecutionCommand

	/**
		* To be called when there is a change in resources, jobs, etc...
		* Potential changes:
		* <ol>
		*   <li>Arrival of demand</li>
		*   <li>Delivery of fulfillmemt</li>
		*   <li>Arrival of materials</li>
		*   <li>Completion of task by executor</li>
		*   <
		* </ol>
		*/
	def normalizeExecutionState(): Unit = {

	}


	// From Execution Observer
	override def receiveLoad(from: ActorRef, via: DirectedChannel.End[M], load: M)(implicit at: Long): Unit = {
		// find suitable active Task

		// If none, find suitable ready Task
		// if none, find suitable accepted request and make it ready
	}


	override def startTask(from: ActorRef, sourceCmdId: String, materials: Seq[Material])(implicit at: Long): Unit = {

	}

	override def stageLoad(from: ActorRef, sourceCmdId: String, material: Option[Material])(implicit at: Long): Unit = {}

	override def completeTask(from: ActorRef, sourceCommandId: String, materials: Seq[Material], results: Seq[Material]): Unit = {
		doFinalizeTask(sourceCommandId)
	}

	override def deliverResult(from: ActorRef, sourceCommandId: String, via: DirectedChannel.Start[M], result: M)
	: Unit = {}
}
