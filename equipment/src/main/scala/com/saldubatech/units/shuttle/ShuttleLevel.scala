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
	case class PullLoad(override val load: MaterialLoad, override val idx: Int)
		extends Channel.PulledLoadImpl[MaterialLoad](load, idx)
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
			case cmd@Store(from, to) =>
				(inboundSlots.get(from).filter(!_.isEmpty), slots.get(to).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.tellTo(shuttle, Shuttle.GoTo(fromLoc))
						fetching(shuttle, cmd)(
							delivering(shuttle, cmd)(
								idle,
								errorLoaded
							),
							waitingForLoad(shuttle, inboundChannels(from), inboundSlots(from))(
								delivering(shuttle, cmd)(
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
			case OutputFromTray(toChannelName) => idle
			case PutawayFromTray(toLoc) => idle
		}
	}

	private def fetching(shuttle: ProcessorRef, cmd: ShuttleLevel.ExternalCommand)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) =>
				ctx.tellTo(shuttle, Shuttle.Load(destination))
				loading(success)(masterContext)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedEmpty(cmd, s"Could not load tray"))
				failure
		}
	}

	private def loading(continue: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Loaded(Shuttle.Load(loc)) =>
				continue
		}
	}

	private def delivering(shuttle: ProcessorRef, cmd: ShuttleLevel.ExternalCommand)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) =>
				ctx.tellTo(shuttle, Shuttle.Unload(destination))
				unloading(success)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedEmpty(cmd, s"Could not unload tray"))
				failure
		}
	}

	private def unloading(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Unloaded(Shuttle.Unload(loc), load) =>
				continue
		}
	}

	private def waitingForLoad(shuttle: ProcessorRef, from: Channel.End[MaterialLoad, ShuttleLevelMessage], fromLoc: ShuttleLocation)(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case tr :Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
				if(fromLoc isEmpty) {
					fromLoc store tr.load
					from.get(tr.load)
					ctx.tellTo(shuttle, Shuttle.Load(fromLoc))
					loading(continue)
				} else {
					throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
				}
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
		}
	}
/*	val ch: Channel2[MaterialLoad, ShuttleLevelMessage, ShuttleLevelMessage] = new Channel2(() => Some(33L), Set.empty, "asdf")
	implicit val sender: Channel2.Source[MaterialLoad, ShuttleLevelMessage] = null
	implicit val receiver: Channel2.Sink[MaterialLoad, ShuttleLevelMessage] = null
	val chOps: Channel2.Ops[MaterialLoad, ShuttleLevelMessage, ShuttleLevelMessage] =
		new Channel2.Ops[MaterialLoad, ShuttleLevelMessage, ShuttleLevelMessage](ch)

	val ep: Channel2.End[MaterialLoad, ShuttleLevelMessage] = chOps.end
	val wfl = ep.loadReceiver


 */
	private def waitingForSlot(shuttle: ProcessorRef, to: Channel.Start[MaterialLoad, ShuttleLevelMessage], toLoc: ShuttleLocation)(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case tr: Channel.AcknowledgeLoad[MaterialLoad] =>
				ctx.tellTo(shuttle, Shuttle.Unload(toLoc))
				unloading(continue)
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for Shuttle to arrive to delivery location")
		}
	}

	val processor = new Processor[ShuttleLevelMessage](name, clockRef, controllerRef, configurer)

}
