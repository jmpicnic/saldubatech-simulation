/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.equipment.shuttle

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Aisle.{LevelLocator, Side}
import com.saldubatech.base.Processor.{ConfigureOwner, ExecutionCommandImpl, ExecutionResource, Task}
import com.saldubatech.base.{CarriagePhysics, DirectedChannel, Material, ProcessorHelper}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object ShuttleLevelExecutor {


	def apply(name: String,
	          physics: CarriagePhysics,
	          aisleLength: Int,
	          levelInboundChannel: DirectedChannel[Material],
	          levelOutboundChannel: DirectedChannel[Material],
	          initialPosition: LevelLocator = LevelLocator(Side.LEFT, 0),
	          initialInventory: Map[LevelLocator, Material] = Map.empty
			)(implicit gw: Gateway): ActorRef =
		gw.simActorOf(Props(
			new ShuttleLevelExecutor(name,
				physics,
				aisleLength,
				levelInboundChannel,
				levelOutboundChannel,
				initialPosition,
				initialInventory)),
			name)

	class StorageExecutionCommand(name: String = java.util.UUID.randomUUID().toString) extends ExecutionCommandImpl(name)

	case class Inbound(toSlot: LevelLocator) extends StorageExecutionCommand {
		assert(toSlot.idx >= 0, "Negative positions not allowed")
	}
	case class Outbound(fromSlot: LevelLocator)	extends StorageExecutionCommand {
		assert(fromSlot.idx >= 0, "Negative positions not allowed")
	}
	case class Groom(from: LevelLocator, to: LevelLocator)  extends StorageExecutionCommand {
		assert(from.idx >= 0 && to.idx >= 0, "Only positive positions allowed for grooming")
	}
}

class ShuttleLevelExecutor(name: String,
                           physics: CarriagePhysics,
                           aisleLength: Int,
                           inboundChannel: DirectedChannel[Material],
                           outboundChannel: DirectedChannel[Material],
                           initialPosition: LevelLocator = LevelLocator(Side.LEFT, 0),
                           initialInventory: Map[LevelLocator, Material] = Map.empty
                          )(implicit gw: Gateway)
extends SimActorImpl(name, gw)
with ProcessorHelper[ShuttleLevelExecutor.StorageExecutionCommand, ExecutionResource] {
	import ShuttleLevelExecutor._

	private val inboundEndpoint: DirectedChannel.End[Material] = inboundChannel.end
	private val outboundEndpoint: DirectedChannel.Start[Material] = outboundChannel.start
	private val slots: Map[Side.Value, mutable.ArrayBuffer[Option[Material]]] =
		Map(
			Side.LEFT -> mutable.ArrayBuffer.tabulate[Option[Material]](aisleLength)(elem => initialInventory.get(LevelLocator(Side.LEFT, elem))),
			Side.RIGHT -> mutable.ArrayBuffer.tabulate[Option[Material]](aisleLength)(elem => initialInventory.get(LevelLocator(Side.LEFT, elem)))
		)

	override protected def updateState(at: Long): Unit = {}
		// Nothing to update for now.

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			outboundChannel.registerStart(this)
			inboundChannel.registerEnd(this)
	}

	private def operationalCommandProcessing(from:ActorRef, at: Long): Processing = { case cmd: StorageExecutionCommand => receiveCommand(cmd, at)}

	override def process(from: ActorRef, at: Long): Processing =
		innerProtocol(from, at) orElse
		operationalCommandProcessing(from, at) orElse
		inboundEndpoint.loadReceiving(from, at) orElse
		outboundEndpoint.restoringResource(from, at)

	private object Stage extends Enumeration {
		val WAIT, PICKUP, TRANSFER, DELIVER = new Val()
	}
	private var stage: Stage.Value = Stage.WAIT
	private def innerProtocol(from: ActorRef, at: Long):Processing = {
		stage match {
			case Stage.PICKUP =>
				stage = Stage.TRANSFER
				completeStaging(from, at)
			case Stage.TRANSFER =>
				stage = Stage.DELIVER
				completeMovement(from, at)
			case Stage.DELIVER => nullProcessing
			case Stage.WAIT => { case None => throw new IllegalStateException("Cannot Process Messages through the protocol in WAIT state")}
		}
	}

	override protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit =
		assert(via == inboundEndpoint, "Can only receive loads through the inbound endpoint")

	override protected def localSelectNextExecution(pendingCommands: List[ShuttleLevelExecutor.StorageExecutionCommand],
	                                                availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                                                at: Long): Option[Task[ShuttleLevelExecutor.StorageExecutionCommand,  ExecutionResource]] = {
		if(pendingCommands nonEmpty) {
			val cmd = pendingCommands.head// FIFO
			cmd match {
				case Inbound(toSlot) =>
					val entry = availableMaterials.find(e => e._2 == inboundEndpoint)
					if(entry isDefined) Some(Task[ShuttleLevelExecutor.StorageExecutionCommand, ExecutionResource](cmd, Map(entry.!))(at)) else None
				case _ => Some(Task[ShuttleLevelExecutor.StorageExecutionCommand, ExecutionResource](cmd, Map.empty)(at))
			}
		} else None
	}

	override protected def localInitiateTask(task: Task[ShuttleLevelExecutor.StorageExecutionCommand, ExecutionResource], at: Long): Unit = {
		stage = Stage.PICKUP
		task.cmd match {
			case Inbound(toSlot) =>
				PickUpInbound(toSlot, task.materials.keys.head) ~> self in ((at, physics.timeToStage(currentPosition.idx-(-1))))
			case Groom(fromSlot, toSlot) =>
				PickUpGroom(fromSlot, toSlot) ~> self in ((at, physics.timeToStage(currentPosition.idx-fromSlot.idx)))
			case Outbound(fromSlot) =>
				PickUpOutbound(fromSlot) ~> self in ((at, physics.timeToStage(currentPosition.idx-fromSlot.idx)))
		}
	}

	private case class PickUpInbound(fromSlot: LevelLocator, load: Material)
	private case class PickUpGroom(fromSlot: LevelLocator, toSlot: LevelLocator)
	private case class PickUpOutbound(fromSlot: LevelLocator)
	private case class Store(intoSlot: LevelLocator, load: Material)
	private case class Deliver(load: Material)

	private var currentPosition: LevelLocator = initialPosition

	private def completeStaging(from: ActorRef, at: Long): Processing = {
		case PickUpInbound(toSlot, load) =>
			currentPosition = LevelLocator(toSlot.side, -1)
			stageMaterial(load, inboundEndpoint, at)
			Store(toSlot, load) ~> self in ((at, physics.timeToDeliver(currentPosition.idx-toSlot.idx)))
		case PickUpGroom(fromSlot, toSlot) =>
			currentPosition = fromSlot
			val load = slots(fromSlot.side)(fromSlot.idx).!
			slots(fromSlot.side)(fromSlot.idx) = None
			//stageMaterial(load, at)
			Store(toSlot, load) ~> self in ((at, physics.timeToDeliver(currentPosition.idx-toSlot.idx)))
		case PickUpOutbound(fromSlot) =>
			currentPosition = fromSlot
			val load = slots(fromSlot.side)(fromSlot.idx).!
			slots(fromSlot.side)(fromSlot.idx) = None
			stageMaterial(load, at)
			Deliver(load) ~> self in ((at, physics.timeToDeliver(currentPosition.idx-(-1))))
	}

	private def completeMovement(from: ActorRef, at: Long): Processing = {
		case Store(toSlot, load) =>
			log.debug(s"Completed Store Movement: $toSlot, $load")
			currentPosition = toSlot
			slots(toSlot.side)(toSlot.idx) = load.?
			completeCommand(Seq.empty, at)
		case Deliver(load) =>
			currentPosition = LevelLocator(currentPosition.side, -1)
			tryDelivery(load, outboundEndpoint, at)
	}

	override protected def localFinalizeDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit = {
		assert(via == outboundEndpoint, "Can only send through the outbound endpoint")
		completeCommand(Seq(load), tick)
		stage = Stage.WAIT
	}
}
