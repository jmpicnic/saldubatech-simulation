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
import com.saldubatech.base.Aisle.LevelLocator
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.{Aisle, Identification, Material}
import com.saldubatech.ddes.Gateway
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.v0.Node.{Demand, Job, Request, ToteId}

import scala.collection.mutable

object ShuttleById {
	sealed class ShuttleJob extends Identification.Impl with Job
	case class PutAway(tote: Material) extends ShuttleJob
	case class FetchById(toteId: String) extends ShuttleJob

	type ShuttleDemand = Demand[ShuttleJob]
	type ShuttleRequest = Request[ShuttleJob, ShuttleDemand]

}

class ShuttleById(id: String, shuttle: ActorRef, wipLimit: Int, aisleLength: Int,
                  initialIds: Map[String, LevelLocator] = Map.empty)
                 (implicit gw: Gateway)
	extends Node[Material, ShuttleById.ShuttleJob, ShuttleById.ShuttleDemand, ShuttleById.ShuttleRequest](id, wipLimit){
	import ShuttleById._

	private val inventory: mutable.Map[ToteId, LevelLocator] = mutable.Map.empty ++ initialIds
	private val reverseInventory: mutable.Map[LevelLocator, ToteId] = mutable.Map.empty ++ initialIds.map(e => e._2 -> e._1)

	private val slots: Map[Aisle.Side.Value, List[LevelLocator]] = Map(
		Aisle.Side.LEFT -> List.tabulate(aisleLength)(idx => LevelLocator(Aisle.Side.LEFT, idx)),
		Aisle.Side.RIGHT -> List.tabulate(aisleLength)(idx => LevelLocator(Aisle.Side.RIGHT, idx)))

	private val empties: Map[Aisle.Side.Value, List[LevelLocator]] = Map(
		Aisle.Side.LEFT -> slots(Aisle.Side.LEFT).filter(p => !initialIds.values.toSet.contains(p)) ,
		Aisle.Side.RIGHT -> slots(Aisle.Side.RIGHT).filter(p => !initialIds.values.toSet.contains(p)))

	private def acceptedInboundRequests(cond: Option[ShuttleJob => Boolean] = None): Set[ShuttleRequest] =
		(acceptedRequests.values++readyTasks.keySet++activeTasks.values).filter{
			_.demand.job match {
				case j: FetchById => false
				case j: PutAway => cond.isEmpty || cond.exists(c => c(j))
			}
		}.toSet

	private def acceptedOutboundRequests(cond: Option[ShuttleJob => Boolean] = None): Set[ShuttleRequest] =
		(acceptedRequests.values++readyTasks.keySet++activeTasks.values).filter{
			_.demand.job match {
				case j: PutAway => false
				case j: FetchById => cond.isEmpty || cond.exists(c => c(j))
			}
		}.toSet

	private def availableToStore: Int = empties.size - acceptedInboundRequests().size + acceptedOutboundRequests().size
	private def inventoryAvailableToDeliver(toteId: String): Int = (acceptedInboundRequests(Some({ //
				case FetchById(fetchingId) if fetchingId == toteId => true
				case _ => false
			})).size - acceptedOutboundRequests(Some({
				case PutAway(tote) if tote.uid == toteId => true
				case _ => false
			})).size
				+ (if(inventory.contains(toteId)) 1 else 0))

	override def configure: Configuring = ???

	private def demandProtocolProcessing(from: ActorRef, at: Long):Processing = {
		case demand: Demand[FetchById] => {}// maybeReceiveDemand(from, at, demand)
	}

	override def process(from: ActorRef, at: Long): Processing =
		demandProtocolProcessing(from, at) orElse notificationReceiver(from, at)

	def isAcceptable(demand: ShuttleDemand): Boolean = {
		demand.job match {
			case job @ PutAway(tote) => availableToStore >= 0 // Total committed space is =< currentAvailable
			case job @ FetchById(toteId) => inventoryAvailableToDeliver(toteId) >= 0
		}
	}

	val availableInboundMaterials: mutable.Map[Material, DirectedChannel.End[Material]] = mutable.Map.empty
	override def receiveLoad(from: ActorRef,via: DirectedChannel.End[Material],load: Material)(implicit at: Long)
	: Unit = {
		availableInboundMaterials.put(load, via)
		// Find next job to process
	}


  def commandForRequest(request: ShuttleById.ShuttleRequest): com.saldubatech.base.processor.Task.ExecutionCommand = ???

  def requestForDemand(demand: ShuttleById.ShuttleDemand,from: akka.actor.ActorRef,at: Long): ShuttleById.ShuttleRequest = ???
}
