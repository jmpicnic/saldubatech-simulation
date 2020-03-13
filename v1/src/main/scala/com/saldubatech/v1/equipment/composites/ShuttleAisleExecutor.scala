/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.v1.equipment.composites

import akka.actor.{ActorRef, Props}
import com.saldubatech.v1.base.Aisle.LevelLocator
import com.saldubatech.v1.base.processor.Processor._
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.base.{CarriagePhysics, Material}
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimDSL._
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl}
import com.saldubatech.v1.equipment.composites.StorageModule.{Groom, Inbound, InitializeInventory, Outbound, StorageAisleCommand}
import com.saldubatech.v1.equipment.units.shuttle.{LiftExecutor, ShuttleLevelExecutor}
import com.saldubatech.util.Lang._

import scala.collection.mutable
import scala.languageFeature.postfixOps

object ShuttleAisleExecutor {
	type Continuation = (ActorRef, ActorRef, Long) => Processing

	def apply(name: String,
	          inboundChannel: DirectedChannel[Material],
	          outboundChannel: DirectedChannel[Material],
	          nLevels: Int,
	          liftPhysics: CarriagePhysics,
	          shuttlePhysics: CarriagePhysics,
	          aisleLength: Int,
	          initialInventory: Map[Int, Map[LevelLocator, Material]] = Map.empty,
	          ioLevel: Int = 0)(implicit gw: Gateway): ActorRef =
		gw.simActorOf(Props(new ShuttleAisleExecutor(
			name,
			inboundChannel,
			outboundChannel,
			nLevels,
			liftPhysics,
			shuttlePhysics,
			aisleLength,
			initialInventory,
			ioLevel)), name)
}

class ShuttleAisleExecutor(name: String,
                           inboundChannel: DirectedChannel[Material],
                           outboundChannel: DirectedChannel[Material],
                           nLevels: Int,
                           liftPhysics: CarriagePhysics,
                           shuttlePhysics: CarriagePhysics,
                           aisleLength: Int,
                           initialContents: Map[Int, Map[LevelLocator, Material]] = Map.empty,
                           ioLevel: Int = 0)(implicit gw: Gateway)
	extends SimActorImpl(name, gw)
	{
		import ShuttleAisleExecutor._

		private val levelInboundChannels: List[DirectedChannel[Material]] =
			(0 to nLevels).toList.map(
				idx => DirectedChannel[Material](2)
			)
		private val levelOutboundChannels: List[DirectedChannel[Material]] =
			(0 to nLevels).toList.map(
				idx => DirectedChannel[Material](2)
			)

		private val levels: List[ActorRef] =
			(0 to nLevels).map(idx =>
				ShuttleLevelExecutor(
					name + s"_level_$idx",
					shuttlePhysics,
					aisleLength,
					levelInboundChannels(idx),
					levelOutboundChannels(idx),
					initialInventory = if(initialContents contains idx) initialContents(idx) else Map.empty
				)
			).toList

		private val liftCommander = new LiftExecutor.Commander(inboundChannel.end, outboundChannel.start)
		private val lift: ActorRef = LiftExecutor(
			name + "_Lift",
			liftPhysics,
			inboundChannel,
			outboundChannel,
			(1 to nLevels).toList.map(
				idx => (levelInboundChannels(idx), levelOutboundChannels(idx))),
			inboundChannel.end,
			ioLevel
		)


		private var _owner: Option[ActorRef] = None

		private def owner: ActorRef = _owner.!

		override def configure: Configuring = {
			case ConfigureOwner(p_owner) =>
				_owner = p_owner.?
				cascadeConfiguration(lift, ConfigureOwner(self))
				levels.foreach(l => cascadeConfiguration(l, ConfigureOwner(self)))
			case InitializeInventory(invMap) =>
				levels.zipWithIndex.foreach{
					case (level, idx) =>
						cascadeConfiguration(
							levels(idx),
							ShuttleLevelExecutor.InitializeInventory(invMap.flatMap {
								case (loc, mat) => if (loc.level == idx) Some(loc.inLevel -> mat) else None
							})
						)
				}
		}

		private val pendingContinuations: mutable.Queue[Continuation] = mutable.Queue.empty
		private val pendingCommands: mutable.Queue[StorageAisleCommand] = mutable.Queue.empty

		override def process(from: ActorRef, at: Long): Processing =
			if(pendingCommands nonEmpty) {
				log.debug(s"Processing Message with PendingCommands")
				externalCommandProcessing(from, at) orElse pendingContinuations.head(self, from, at)
			} else {
				log.debug(s"Processing Message with NO PendingCommands")
				externalCommandProcessing(from, at) orElse {
					case ReceiveLoad(via, load) if from == lift =>
						ReceiveLoad(via, load) ~> owner now at
				}
			}

		private def externalCommandProcessing(from: ActorRef, at: Long): Processing = {
			case c: Inbound if from == owner => startInbound(c, at)
			case c: Outbound if from == owner => startOutbound(c, at)
			case c: Groom if from == owner => startGroom(c, at)
		}

		private def enqueueCommand(cmd: StorageAisleCommand, cont: Continuation): Unit = {
			pendingCommands enqueue cmd
			pendingContinuations enqueue cont
		}
		private def dequeueCommand: Unit = {pendingCommands.dequeue; pendingContinuations.dequeue}
		private def startInbound(cmd: Inbound, at: Long): Unit = {
			enqueueCommand(cmd, inboundContinuation(levels(cmd.to.level)))
			liftCommander.inbound(levelInboundChannels(cmd.to.level).start, cmd.loadId) ~> lift now at
			ShuttleLevelExecutor.Inbound(cmd.to.inLevel) ~> levels(cmd.to.level) now at
		}

		private def inboundContinuation(shuttle: ActorRef): Continuation = (host, from, at) => {
			case ReceiveLoad(via,material) if from == lift =>
				ReceiveLoad(via, material) ~> owner now at
			case StartTask(cmd, materials) if from == lift =>
				StartTask(pendingCommands.head.uid, materials) ~> owner now at
			case StageLoad(cmd, load) if from == lift =>
				// Lift picked up from incoming, done aisle staging
				StageLoad(pendingCommands.head.uid, load) ~> owner now at
			case DeliverResult(cmd, via, result) if from == lift =>
			case CompleteTask(cmd, inputs, outputs) if from == lift =>  // Lift is done. Could be ready to accept another job...?
			case ReceiveLoad(via, material) if from == shuttle =>
			case StartTask(cmd, materials) if from == shuttle =>
			case StageLoad(cmd, load) if from == shuttle => 	// Shuttle picked up from lift
			case DeliverResult(cmd, via, result) if from == shuttle =>
				DeliverResult(pendingCommands.head.uid, via, result)
			case CompleteTask(cmd, inputs, outputs) if outputs.isEmpty && from == shuttle =>
				// Shuttle is done, Inbound Complete.
				CompleteTask(pendingCommands.head.uid, inputs, Seq()) ~> owner now at
				dequeueCommand
		}

		private def startOutbound(cmd: Outbound, at: Long): Unit = {
			enqueueCommand(cmd, outboundContinuation(levels(cmd.from.level)))
			ShuttleLevelExecutor.Outbound(cmd.from.inLevel) ~> levels(cmd.from.level) now at
			liftCommander.outbound(levelOutboundChannels(cmd.from.level).end, None) ~> lift now at
		}

		private def outboundContinuation(shuttle: ActorRef): Continuation = (host, from, at) => {
			case StartTask(cmd, materials) if from == shuttle =>
				StartTask(pendingCommands.head.uid, materials) ~> owner now at
			case StageLoad(cmd, load) if from == shuttle =>
				// Shuttle picked up from slot, done Aisle Staging
				StageLoad(pendingCommands.head.uid, load) ~> owner now at
			case DeliverResult(cmd, via, result) if from == shuttle =>
			case CompleteTask(cmd, inputs, outputs) if inputs.isEmpty && from == shuttle =>  // Shuttle is done.
			case ReceiveLoad(via, load) if from == lift =>
			case StartTask(cmd, materials) if from == lift =>
			case StageLoad(cmd, load) if from == lift => // Lift picked up from shuttle
			case DeliverResult(cmd, via, result) if from == lift =>
				DeliverResult(pendingCommands.head.uid, via, result) ~> owner now at
			case CompleteTask(cmd, inputs, outputs) if from == lift =>
				// Lift is done. Outbound Complete
				log.debug("Finalizing Outbound Command")
				CompleteTask(pendingCommands.head.uid, Seq(), outputs) ~> owner now at
				dequeueCommand
		}

		private def startGroom(cmd: Groom, at: Long): Unit = {
			enqueueCommand(cmd, groomingContinuation(levels(cmd.from.level), levels(cmd.to.level)))
			ShuttleLevelExecutor.Outbound(cmd.from.inLevel) ~> levels(cmd.from.level) now at
			liftCommander.transfer(
				levelOutboundChannels(cmd.from.level).end,
				levelInboundChannels(cmd.to.level).start,
				None
			) ~> lift now at
			ShuttleLevelExecutor.Inbound(cmd.to.inLevel) ~> levels(cmd.to.level) now at
		}

		private def groomingContinuation(fromShuttle: ActorRef, toShuttle: ActorRef): Continuation = (host, from, at) => {
			case StartTask(cmd, materials) if from == fromShuttle =>
				StartTask(pendingCommands.head.uid, materials) ~> owner now at
			case StageLoad(cmd, load) if from == fromShuttle =>
				// Shuttle picked up from slot, done Aisle Staging
				log.debug("Grooming -- Retrieve Staging")
				StageLoad(pendingCommands.head.uid, load) ~> owner now at
			case DeliverResult(cmd, via, result) if from == fromShuttle =>
			case CompleteTask(cmd, inputs, outputs) if inputs.isEmpty && from == fromShuttle => log.debug("Grooming -- Retrieve Complete") // from-shuttle is done.
			case ReceiveLoad(via, load) if from == lift =>
			case StartTask(cmd, materials) if from == lift =>
			case StageLoad(cmd, load) if from == lift => log.debug("Grooming -- Transfer Staging") // Lift picked up from shuttle
			case DeliverResult(cmd, via, result) if from == lift =>
			case CompleteTask(cmd, inputs, outputs) if from == lift => log.debug("Grooming -- Transfer Complete") // Lift is done.
			case ReceiveLoad(via, load) if from == toShuttle =>
			case StartTask(cmd, materials) if from == toShuttle =>
			case StageLoad(cmd, load) if from == toShuttle => log.debug("Grooming -- Deliver Staging") // toShuttle picked up from lift
			case DeliverResult(cmd, via, result) if from == toShuttle => DeliverResult(pendingCommands.head.uid, via, result) ~> owner now at
			case CompleteTask(cmd, inputs, outputs) if outputs.isEmpty && from == toShuttle =>
				// to-shuttle is done, Groom Complete.
				log.debug("Grooming -- Deliver Complete")
				CompleteTask(pendingCommands.head.uid, inputs, outputs) ~> owner now at
				dequeueCommand
		}

	}
