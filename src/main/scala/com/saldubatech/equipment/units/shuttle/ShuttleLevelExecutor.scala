/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.shuttle

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Aisle.{LevelLocator, Side}
import com.saldubatech.base.processor.Processor.ConfigureOwner
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.{ProcessorHelper, Task}
import com.saldubatech.base.{CarriagePhysics, Material}
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.resource.Slot
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

	case class InitializeInventory(inv: Map[LevelLocator, Material])

	class StorageExecutionCommand(name: String = java.util.UUID.randomUUID().toString) extends Task.ExecutionCommandImpl(name)

	case class Inbound(toSlot: LevelLocator) extends StorageExecutionCommand {
		assert(toSlot.idx >= 0, "Negative positions not allowed")
	}
	case class Outbound(fromSlot: LevelLocator)	extends StorageExecutionCommand {
		assert(fromSlot.idx >= 0, "Negative positions not allowed")
	}
	case class Groom(from: LevelLocator, to: LevelLocator)  extends StorageExecutionCommand {
		assert(from.idx >= 0 && to.idx >= 0, "Only positive positions allowed for grooming")
	}

	case class FailedCommand(c: StorageExecutionCommand, msg: String)

	case class ShuttleTask(command: StorageExecutionCommand,
	                       materials: Map[Material, DirectedChannel.End[Material]],
	                       slot: Slot[Material])(implicit at: Long)
		extends Task[StorageExecutionCommand, Material, Material, Slot[Material]](command, materials, slot.?) {
		override def isAcceptable(mat: Material): Boolean = true

		override def addMaterialPostStart(mat: Material, via: DirectedChannel.End[Material], at: Long): Boolean = false

		override protected def isReadyToStart: Boolean = true

		override protected def doStart(at: Long): Boolean = true

		override protected def prepareToComplete(at: Long): Boolean = true

		override protected def doProduce(at: Long): Boolean = {
			_products ++ _materials.keySet
			true
		}
	}

}

class ShuttleLevelExecutor(val name: String,
                           physics: CarriagePhysics,
                           aisleLength: Int,
                           inboundChannel: DirectedChannel[Material],
                           outboundChannel: DirectedChannel[Material],
                           initialPosition: LevelLocator = LevelLocator(Side.LEFT, 0),
                           initialInventory: Map[LevelLocator, Material] = Map.empty
                          )(implicit gw: Gateway)
extends SimActorImpl(name, gw)
with ProcessorHelper[ShuttleLevelExecutor.StorageExecutionCommand, Slot[Material], Material, Material,
	ShuttleLevelExecutor.ShuttleTask] {
	import ShuttleLevelExecutor._

	lazy val carriage: Slot[Material] = Slot[Material]()
	override protected def resource: Slot[Material] = carriage

	private val inboundEndpoint: DirectedChannel.End[Material] = inboundChannel.end
	private val outboundEndpoint: DirectedChannel.Start[Material] = outboundChannel.start
	private val slots: Map[Side.Value, mutable.ArrayBuffer[Option[Material]]] =
		Map(
			Side.LEFT -> mutable.ArrayBuffer.tabulate[Option[Material]](aisleLength)(elem => initialInventory.get(LevelLocator(Side.LEFT, elem))),
			Side.RIGHT -> mutable.ArrayBuffer.tabulate[Option[Material]](aisleLength)(elem => initialInventory.get(LevelLocator(Side.LEFT, elem)))
		)

	override def configure: Configuring = {
		case ConfigureOwner(p_owner) =>
			configureOwner(p_owner)
			outboundChannel.registerStart(this)
			inboundChannel.registerEnd(this)
		case InitializeInventory(inventory) =>
			inventory.foreach{
				case (loc, mat) => slots(loc.side)(loc.idx) = Some(mat)
			}
	}

	override protected def updateState(at: Long): Unit = {
		// Nothing to update for now.
	}

	private def commandReceiver(from:ActorRef, at: Long)
	: Processing = {case cmd: StorageExecutionCommand => receiveCommand(cmd, at)}

	override def process(from: ActorRef, at: Long): Processing =
		innerProtocol(from, at) orElse
		commandReceiver(from, at) orElse
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
			case Stage.WAIT =>
			{case None => throw new IllegalStateException("Cannot Process Messages through the protocol in WAIT state")}
		}
	}

	/*override protected def localSelectNextExecution(pendingCommands: List[ShuttleLevelExecutor.StorageExecutionCommand],
	                                                availableMaterials: Map[Material, DirectedChannel.End[Material]],
	                                                at: Long): Option[ShuttleTask] = {
		if(pendingCommands nonEmpty) {
			val cmd = pendingCommands.head// FIFO
			cmd match {
				case Inbound(toSlot) =>
					val entry = availableMaterials.find(e => e._2 == inboundEndpoint)
					if(entry isDefined) Some(ShuttleTask(cmd, Map(entry.!))(at)) else None
				case _ => Some(ShuttleTask(cmd, Map.empty)(at))
			}
		} else None
	}*/

	override protected def localReceiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit =
		assert(via == inboundEndpoint, "Can only receive loads through the inbound endpoint")

	override protected def collectMaterials(cmd: StorageExecutionCommand, resource: Slot[Material],
	                                        available: mutable.Map[Material, DirectedChannel.End[Material]])
	:Map[Material, DirectedChannel.End[Material]] = {
		cmd match {
			case Inbound(toSlot) => available.flatMap(e => if(e._2 == inboundEndpoint) e.? else None).toMap
			case _ =>  Map.empty// No material for commands that must retrieve from storage
		}
	}

	override protected def newTask(cmd: StorageExecutionCommand,
	                               materials: Map[Material, DirectedChannel.End[Material]],
	                               rs: Slot[Material], at: Long): Option[ShuttleTask] = {
		cmd match {
				case Inbound(toSlot) =>
					val entry = materials.find(e => e._2 == inboundEndpoint)
					if(entry isDefined) Some(ShuttleTask(cmd, Map(entry.!), rs)(at)) else None
				case _ => Some(ShuttleTask(cmd, Map.empty, rs)(at))
			}
	}

	override protected def triggerTask(task: ShuttleTask, at: Long): Unit = {
		stage = Stage.PICKUP
		task.cmd match {
			case Inbound(toSlot) =>
				PickUpInbound(toSlot, task.initialMaterials.keys.head) ~> self in ((at, physics.timeToStage(currentPosition.idx-(-1))))
			case Groom(fromSlot, toSlot) =>
				PickUpGroom(fromSlot, toSlot) ~> self in ((at, physics.timeToStage(currentPosition.idx-fromSlot.idx)))
			case Outbound(fromSlot) =>
				PickUpOutbound(fromSlot) ~> self in ((at, physics.timeToStage(currentPosition.idx-fromSlot.idx)))
		}
	}

	override protected def loadOnResource(rs: Option[Slot[Material]], material: Option[Material]): Unit = {
		log.debug(s"Loading on Resource $rs")
		assert(rs.! == carriage, s"Only resource available is $carriage")
		assert(carriage << material.!, s"Carriage $carriage should be able to accept material")
	}

	override protected def offloadFromResource(resource: Option[Slot[Material]], product: Set[Material]): Unit = {
		assert(resource.! == carriage, s"Only resource available is $carriage")
		val ct = carriage.>>
		log.debug(s"Emptying Carriage: $ct")
		assert(ct isDefined, s"Carriage $carriage was already empty: $ct")
	}

	override protected def localFinalizeDelivery(load: Material, via: DirectedChannel.Start[Material], tick: Long): Unit = {
		assert(via == outboundEndpoint, "Can only send through the outbound endpoint")
		completeCommand(Seq(load), tick)
		stage = Stage.WAIT
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
			stageMaterial(load, inboundEndpoint.?, at)
			Store(toSlot, load) ~> self in ((at, physics.timeToDeliver(currentPosition.idx-toSlot.idx)))
		case c @ PickUpGroom(fromSlot, toSlot) =>
			currentPosition = fromSlot
			val maybeLoad = slots(fromSlot.side)(fromSlot.idx)
			if(maybeLoad isEmpty)
				assert(false, s"No inventory at location $fromSlot")
				//FailedCommand(???, s"No inventory at location $fromSlot") ~> owner now at
			else {
				stageMaterial(maybeLoad.!, None, at)
				slots(fromSlot.side)(fromSlot.idx) = None
				Store(toSlot, maybeLoad.!) ~> self in ((at, physics.timeToDeliver(currentPosition.idx - toSlot.idx)))
			}
		case PickUpOutbound(fromSlot) =>
			currentPosition = fromSlot
			val maybeLoad = slots(fromSlot.side)(fromSlot.idx)
			slots(fromSlot.side)(fromSlot.idx) = None
			if(maybeLoad isEmpty)
				assert(false, s"No inventory at location $fromSlot")
				//FailedCommand(???, s"No inventory at location $fromSlot") ~> owner now at
			else {
				stageMaterial(maybeLoad.!, None, at)
				Deliver(maybeLoad.!) ~> self in ((at, physics.timeToDeliver(currentPosition.idx-(-1))))
			}
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

}
