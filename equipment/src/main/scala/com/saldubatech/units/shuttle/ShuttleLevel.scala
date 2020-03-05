/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Processor.{CommandContext, ProcessorRef}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.shuttle.Shuttle.{LevelLocator, OnLeft, OnRight, ShuttleLocation}

import scala.collection.mutable

object ShuttleLevel {




	private def inboundLocator(idx: Int): LevelLocator = OnLeft(idx)
	private def outboundLocator(idx: Int): LevelLocator = OnRight(idx)

	trait ShuttleLevelMessage extends Identification

	sealed abstract class ShuttleCommand extends Identification.Impl() with ShuttleLevelMessage
	case object NoConfigure extends ShuttleCommand
	trait ExternalCommand extends ShuttleCommand
	trait InboundCommand extends ExternalCommand {
		val from: String
	}
	trait OutboundCommand extends ExternalCommand {
		val to: String
	}
	case class Store(override val from: String, to: LevelLocator) extends InboundCommand
	case class Retrieve(from: LevelLocator, override val to: String) extends OutboundCommand
	case class LoopBack(override val from: String, override val to: String) extends InboundCommand with OutboundCommand
	case class OutputFromTray(override val to: String) extends OutboundCommand
	case class Groom(from: LevelLocator, to: LevelLocator) extends ExternalCommand
	case class PutawayFromTray(to: LevelLocator) extends ExternalCommand

	sealed trait ShuttleNotification extends ShuttleLevelMessage
	sealed trait ExternalNotification extends ShuttleNotification
	case class InternalFailure(sig: ShuttleLevelMessage, reasong: String) extends Identification.Impl() with ExternalNotification
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class FailedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with ExternalNotification
	case class LoadArrival(fromCh: String) extends Identification.Impl() with ExternalNotification


	case class Mission(source: Processor.ProcessorRef, cmd: ExternalCommand, at: Clock.Tick)
	sealed trait InternalCommand extends ShuttleCommand
	case class Fetch(from: LevelLocator, mission: Mission) extends InternalCommand
	case class Drop(to: LevelLocator, mission: Mission) extends InternalCommand
	case class Deliver(ch: Channel.Start[MaterialLoad, ShuttleLevelMessage], onSlot: String, mission: Mission) extends InternalCommand
	case class Receive(ch: Channel.End[MaterialLoad, ShuttleLevelMessage], mission: Mission) extends InternalCommand

	sealed trait InternalNotification extends ShuttleNotification
	case class ArriveToDestination(loc: LevelLocator, mission: Mission) extends Identification.Impl() with InternalNotification
	case class PickupComplete(mission: Mission) extends Identification.Impl() with InternalNotification
	case class DropoffComplete(mission: Mission) extends Identification.Impl() with InternalNotification

	sealed trait MaterialMovement extends ShuttleLevelMessage
	case class AcknowledgeLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
		extends Channel.AckLoadImpl[MaterialLoad](channel, load, resource)
		with MaterialMovement
	case class TransferLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
			extends Channel.TransferLoadImpl[MaterialLoad](channel, load, resource)
			with MaterialMovement
	case class ConsumeLoad(override val channel: String)
		extends Channel.LoadConsumedImpl[MaterialLoad](channel)
			with MaterialMovement

}

import ShuttleLevel._
class ShuttleLevel[ConnectProfile0, ConnectProfile1](name: String, depth: Int, initialInventory: Map[LevelLocator, MaterialLoad], shuttleProcessor: Processor[Shuttle.ShuttleSignal], initialPosition: LevelLocator = OnRight(0))
                                                    (implicit clockRef: ActorRef[ClockMessage], controllerRef: ActorRef[ControllerMessage]) extends Identification.Impl(name) {
	private val slots: Map[LevelLocator, Shuttle.ShuttleLocation] = (0 until depth).flatMap(idx => Seq(OnRight(idx) -> Shuttle.ShuttleLocation(OnRight(idx)), OnLeft(idx) -> Shuttle.ShuttleLocation(OnLeft(idx)))).toMap
	initialInventory.foreach(e => slots(e._1).store(e._2))

	private var shuttle: ActorRef[Processor.ProcessorMessage] = null

	private val configurer = new Processor.DomainConfigure[ShuttleLevelMessage] {
		override def configure(config: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = config match {
			case ShuttleLevel.NoConfigure =>
				shuttle = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
				ctx.tellTo(shuttle, Shuttle.Configure(slots(initialPosition)))
				idle
		}
	}
	private val inboundSlots: Map[String, ShuttleLocation] = Map.empty
	private val reverseInboundSlots = inboundSlots.map(e => e._2 -> e._1)
	private val inboundChannels: Map[String, Channel.End[MaterialLoad,ShuttleLevelMessage]] = Map.empty

	private val idle: Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			//new Processor.DomainRun[ShuttleLevelMessage] {
			//override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = processMessage match {
			case cmd@Store(from, to) =>
				(inboundSlots.get(from).filter(!_.isEmpty), slots.get(to).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.tellTo(shuttle, Shuttle.GoTo(fromLoc))
						ShuttleLevelAux.fetching(shuttle, cmd)(
							ShuttleLevelAux.delivering(shuttle, cmd)(
								idle,
								errorLoaded
							),
							ShuttleLevelAux.waitingForLoad(shuttle, inboundChannels(from), inboundSlots(from))(
								ShuttleLevelAux.delivering(shuttle, cmd)(
									idle,
									errorLoaded
								)
							)
						)
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd"))
						idle
				}
		}
	}






	private def errorLoaded: Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			//new Processor.DomainRun[ShuttleLevelMessage] {
			//override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = processMessage match {
				case OutputFromTray(toChannelName) => idle
				case PutawayFromTray(toLoc) => idle
			}
		}

	val processor = new Processor[ShuttleLevelMessage](name, clockRef, controllerRef, configurer)


}
