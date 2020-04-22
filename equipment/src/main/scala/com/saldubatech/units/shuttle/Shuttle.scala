/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor.{DelayedDomainRun, DomainRun}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.carriage.{CarriageComponent, CarriageNotification, CarriageTravel, OnLeft, OnRight, SlotLocator}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object Shuttle {

	trait ShuttleSignal extends Identification

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with ShuttleSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with ShuttleSignal
	trait InboundCommand extends ExternalCommand {
		val from: String
	}
	trait OutboundCommand extends ExternalCommand {
		val to: String
	}
	case class Store(override val from: String, to: SlotLocator) extends InboundCommand
	case class Retrieve(from: SlotLocator, override val to: String) extends OutboundCommand
	case class LoopBack(override val from: String, override val to: String) extends InboundCommand with OutboundCommand
	case class Groom(from: SlotLocator, to: SlotLocator) extends ExternalCommand
	case class PutawayFromTray(to: SlotLocator) extends ExternalCommand
	case class DeliverFromTray(override val to: String) extends OutboundCommand

	sealed abstract class Notification extends Identification.Impl with EquipmentManager.Notification
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Notification
	case class FailedBusy(cmd: ExternalCommand, reason: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	case class Configuration[UpstreamMessage >: ChannelConnections.ChannelSourceMessage, DownStreamMessage >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 depth: Int,
	 physics: CarriageTravel,
	 inbound: Seq[Channel.Ops[MaterialLoad, UpstreamMessage, ShuttleSignal]],
	 outbound: Seq[Channel.Ops[MaterialLoad, ShuttleSignal, DownStreamMessage]])

	case class InitialState(position: Int, inventory: Map[SlotLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
	 initial: InitialState)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val domain = new Shuttle(configuration.name, configuration, initial)
		new Processor[ShuttleSignal](configuration.name, clockRef, simController, domain.configurer)
	}
}

class Shuttle[UpstreamSignal >: ChannelConnections.ChannelSourceMessage, DownstreamSignal >: ChannelConnections.ChannelDestinationMessage]
(name: String,
 configuration: Shuttle.Configuration[UpstreamSignal, DownstreamSignal],
 initial: Shuttle.InitialState) extends Identification.Impl(name) with CarriageComponent.Host[Shuttle.ShuttleSignal, Shuttle.ShuttleSignal] with LogEnabled {
	import Shuttle._

	override type HOST_SIGNAL = ShuttleSignal

	sealed trait CarriageSignal extends ShuttleSignal
	case class Load(override val loc: SlotLocator) extends CarriageComponent.LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends CarriageComponent.UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: Channel.End[MaterialLoad, ShuttleSignal], override val at: SlotLocator)
		extends CarriageComponent.InductCmd[ShuttleSignal](from, at) with CarriageSignal
	case class Discharge(override val to: Channel.Start[MaterialLoad, ShuttleSignal], override val at: SlotLocator)
		extends CarriageComponent.DischargeCmd[ShuttleSignal](to, at) with CarriageSignal

	override type HOST = Shuttle[UpstreamSignal, DownstreamSignal]
	override type LOAD_SIGNAL = Load
	override type UNLOAD_SIGNAL = Unload
	override type INDUCT_SIGNAL = Induct
	override type DISCHARGE_SIGNAL = Discharge

	override def loader(loc: SlotLocator) = Load(loc)
	override def unloader(loc: SlotLocator) = Unload(loc)
	override def inducter(from: Channel.End[MaterialLoad, ShuttleSignal], at: SlotLocator) = Induct(from, at)
	override def discharger(to: Channel.Start[MaterialLoad, ShuttleSignal], at: SlotLocator) = Discharge(to, at)

	private sealed trait WaitFor
	private case object NoLoadWait extends WaitFor
	private case class WaitInductingToDischarge(ep: Channel.Start[MaterialLoad, ShuttleSignal], toLoc: SlotLocator) extends WaitFor
	private case class WaitInductingToStore(toLoc: SlotLocator) extends WaitFor
	private var waitingForLoad: WaitFor = NoLoadWait

	private def inductSink[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage]
	(inboundSlot: SlotLocator,
	 chOps: Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleSignal])(implicit ctx: CTX) = {
		(new Channel.Sink[MaterialLoad, ShuttleSignal] {
			override val ref: Processor.Ref = ctx.aCtx.self

			override def loadArrived(fromEp: Channel.End[MaterialLoad, ShuttleSignal], ld: MaterialLoad, at: Option[Distance] = None)(implicit ctx: Processor.SignallingContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = {
				ctx.aCtx.log.debug(s"ShuttleSink: Received load $ld at channel ${fromEp.channelName}")
				waitingForLoad match {
					case NoLoadWait =>
						ctx.signal(manager, LoadArrival(fromEp.channelName, ld))
						DomainRun.same
					case WaitInductingToDischarge(ep, toLoc) =>
						carriageComponent.inductFrom(fromEp, inboundSlots(fromEp.channelName))
						waitingForLoad = NoLoadWait
						busyGuard orElse channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducting(ep, toLoc))
					case WaitInductingToStore(loc) =>
						carriageComponent.inductFrom(fromEp, inboundSlots(fromEp.channelName))
						waitingForLoad = NoLoadWait
						busyGuard orElse channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(loc))
				}
			}
			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: Processor.SignallingContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = Processor.DomainRun.same
			val end = chOps.registerEnd(this)
		}).end
	}


	private sealed trait WaitForChannel
	private case object NoChannelWait extends WaitForChannel
	private case class WaitDischarging(ch: Channel.Start[MaterialLoad, ShuttleSignal], loc: SlotLocator) extends WaitForChannel
	private var waitingForChannel: WaitForChannel = NoChannelWait
	private def dischargeSource[DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(slot: SlotLocator, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, ShuttleSignal, DownstreamMessageType], host: Processor.Ref) = {
		(new Channel.Source[MaterialLoad, ShuttleSignal] {
			override val ref: Processor.Ref = host
			val start = chOps.registerStart(this)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, ShuttleSignal], load: MaterialLoad)(implicit ctx: CTX): RUNNER =
				waitingForChannel match {
					case NoChannelWait => Processor.DomainRun.same
					case WaitDischarging(ch, loc) =>
						carriageComponent.dischargeTo(ch, loc)
						busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
				}
		}).start
	}

	private val inboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, Channel.End[MaterialLoad, ShuttleSignal]] = mutable.Map.empty
	private var inboundLoadListener: RUNNER = _

	private val outboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, Channel.Start[MaterialLoad, ShuttleSignal]] = mutable.Map.empty
	private var outboundAckListener: RUNNER = _

	private val carriageComponent: CarriageComponent[ShuttleSignal, ShuttleSignal, HOST] =
		new CarriageComponent[ShuttleSignal, ShuttleSignal, HOST](configuration.physics, this).atLocation(initial.position).withInventory(initial.inventory)

	private var manager: Processor.Ref = _
	private var currentCommand: Option[ExternalCommand] = None

	private def configurer: Processor.DomainConfigure[ShuttleSignal] = {
		new Processor.DomainConfigure[ShuttleSignal] {
			override def configure(config: ShuttleSignal)(implicit ctx: CTX): Processor.DomainMessageProcessor[ShuttleSignal] = {
				config match {
					case cmd@NoConfigure =>
						manager = ctx.from
						inboundSlots ++= configuration.inbound.zip(-configuration.inbound.size until 0).map(t => t._1.ch.name -> OnLeft(t._2))
						inboundChannels ++= configuration.inbound.map{chOps =>	chOps.ch.name -> inductSink(inboundSlots(chOps.ch.name), chOps)}
						inboundLoadListener = configuration.inbound.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outbound.zip(-configuration.outbound.size until 0).map{c => c._1.ch.name -> OnRight(c._2)}
						outboundChannels ++= configuration.outbound.map(chOps => chOps.ch.name -> dischargeSource(outboundSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self))
						outboundAckListener = configuration.outbound.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.configureContext.reply(CompletedConfiguration(ctx.aCtx.self))
						IDLE_EMPTY
				}
			}
		}
	}
	private lazy val channelListener = inboundLoadListener orElse outboundAckListener
	private lazy val busyGuard: RUNNER = {
		implicit ctx: CTX => {
			case cmd: ExternalCommand => rejectExternalCommand(cmd, s"XSwitch($name) is busy")
		}
	}
	private def verifyLocator(l: SlotLocator): Option[SlotLocator] = if(l.idx < configuration.depth && l.idx >= 0) Some(l) else None

	private def completeCommand(next: => RUNNER = Processor.DomainRun.same)(implicit ctx: CTX): RUNNER = {
		assert(currentCommand nonEmpty)
		currentCommand.foreach(cmd => ctx.signal(manager, CompletedCommand(cmd)))
		currentCommand = None
		next
	}
	private def executeCommand(cmd: ExternalCommand)(body: => RUNNER)(implicit ctx: CTX): RUNNER =
		if(currentCommand isEmpty) {
			currentCommand = Some(cmd)
			body
		} else {
			ctx.signal(manager, NotAcceptedCommand(cmd, s"Currently processing $currentCommand"))
			Processor.DomainRun.same
		}
	private def rejectExternalCommand(cmd: ExternalCommand, msg: String)(implicit ctx: CTX): RUNNER = {
		ctx.reply(NotAcceptedCommand(cmd, msg))
		Processor.DomainRun.same
	}
	private lazy val IDLE_EMPTY: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@Store(fromCh, toLocator) => executeCommand(cmd){
				(inboundChannels.get(fromCh), verifyLocator(toLocator)) match {
					case (Some(induct), Some(toLoc)) =>
						if(carriageComponent.inspect(toLoc) nonEmpty) failEmpty(s"Target Location to Store ($toLoc) is Full")
						else {
							carriageComponent.inductFrom(induct, inboundSlots(fromCh))
							channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(toLoc))
						}
					case (None, _) =>
						currentCommand = None
						rejectCommand(cmd, s"Inbound Channel does not exist")
					case (_, None) =>
						currentCommand = None
						rejectCommand(cmd, s"Destination $toLocator does not exist")
					case other =>
						currentCommand = None
						rejectCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd@Retrieve(fromLocator,toCh) => executeCommand(cmd){
				(verifyLocator(fromLocator), outboundChannels.get(toCh)) match {
					case (Some(loc), Some(discharge)) =>
						if(carriageComponent.inspect(loc) isEmpty) failEmpty(s"Source Location ($loc) to Retrieve is Empty")
						else {
							carriageComponent.loadFrom(loc)
							channelListener orElse carriageComponent.LOADING(dischargeAfterLoading(discharge, outboundSlots(toCh)))
						}
					case (None, _) =>
						currentCommand = None
						rejectCommand(cmd, s"Inbound Source $fromLocator does not exist")
					case (_, None) =>
						currentCommand = None
						rejectCommand(cmd, "Destination $toCh does not exist")
					case other =>
						currentCommand = None
						rejectCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd@Groom(fromLocator, toLocator) => executeCommand(cmd){
				(verifyLocator(fromLocator), verifyLocator(toLocator)) match {
					case (Some(from), Some(to)) =>
						if(carriageComponent.inspect(to) nonEmpty) failEmpty(s"Target Location to Store ($to) is not empty")
						else if (carriageComponent.inspect(from) isEmpty) failEmpty(s"Source Location ($from) to Retrieve is Empty")
						else {
							carriageComponent.loadFrom(from)
							channelListener orElse carriageComponent.LOADING(unloadAfterLoading(to))
						}
					case (None, _) =>
						currentCommand = None
						rejectCommand(cmd, s"Source location does not exist")
					case (_, None) =>
						currentCommand = None
						rejectCommand(cmd, "Destination does not exist")
					case other =>
						currentCommand = None
						rejectCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd@LoopBack(fromChName, toChName) => executeCommand(cmd){
				(inboundChannels.get(fromChName), outboundChannels.get(toChName)) match {
					case (Some(from), Some(to)) =>
						carriageComponent.inductFrom(from, inboundSlots(fromChName))
						channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducting(to, outboundSlots(toChName)))
					case (None, _) =>
						currentCommand = None
						rejectCommand(cmd, s"Inbound Channel does not exist")
					case (_, None) =>
						currentCommand = None
						rejectCommand(cmd, "Destination does not exist")
					case other =>
						currentCommand = None
						rejectCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd: ExternalCommand => rejectCommand(cmd, "Unknown Command $cmd")
		}
	}
	private lazy val IDLE_FULL: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@PutawayFromTray(toLocator) => executeCommand(cmd){
				verifyLocator(toLocator) match {
					case Some(toLoc) =>
						carriageComponent.unloadTo(toLoc)
						channelListener orElse carriageComponent.UNLOADING(afterUnloading)
					case None => failFull(s"Destination $toLocator does not exist")
				}
			}
			case cmd@DeliverFromTray(chName) => executeCommand(cmd) {
				outboundChannels.get(chName) match {
					case Some(discharge) =>
						carriageComponent.dischargeTo(discharge, outboundSlots(chName))
						channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(discharge, outboundSlots(chName)))
					case None => failFull(s"Outbound Channel $chName does not exist")
				}
			}
			case cmd: ExternalCommand => rejectCommand(cmd, s"Unexpected Command: $cmd")
		}
	}
	private def dischargeAfterLoading(ch: Channel.Start[MaterialLoad, ShuttleSignal], loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => failEmpty("Trying to load from an empty Source")
		}
	}
	private def unloadAfterLoading(loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				carriageComponent.UNLOADING(afterUnloading) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => failEmpty("Trying to load from an empty Source")
		}
	}
	private def storeAfterInducting(loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				carriageComponent.UNLOADING(afterUnloading) orElse channelListener
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitingForLoad = WaitInductingToStore(loc)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
		}
	}
	private def dischargeAfterInducting(ch: Channel.Start[MaterialLoad, ShuttleSignal], loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)) orElse channelListener
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitingForLoad = WaitInductingToDischarge(ch, loc)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
		}
	}

	private def afterUnloading: CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded => completeCommand(IDLE_EMPTY)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull => failFull(s"Target destination is Full")
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => failEmpty("Trying to unload an empty Tray")
		}
	}

	private def rejectCommand(cmd: ExternalCommand, msg: String)(implicit ctx: CTX): RUNNER = {
		ctx.signal(manager, Shuttle.NotAcceptedCommand(cmd, s"Unexpected Command: $cmd"))
		Processor.DomainRun.same
	}
	private def failFull(msg: String)(implicit ctx: CTX): RUNNER = {
			currentCommand.foreach(cmd => ctx.signal(manager, FailedBusy(cmd, msg)))
			currentCommand = None
			IDLE_FULL
	}

	private def failEmpty(msg: String)(implicit ctx: CTX): RUNNER = {
		currentCommand.foreach(cmd => ctx.signal(manager, FailedEmpty(cmd, msg)))
		currentCommand = None
		IDLE_EMPTY
	}

	private def afterTryDischarge(ch: Channel.Start[MaterialLoad, ShuttleSignal], loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				waitingForChannel = NoChannelWait
				currentCommand.foreach(cmd => ctx.signal(manager, CompletedCommand(cmd)))
				currentCommand = None
				IDLE_EMPTY
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitingForChannel = WaitDischarging(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}
}
