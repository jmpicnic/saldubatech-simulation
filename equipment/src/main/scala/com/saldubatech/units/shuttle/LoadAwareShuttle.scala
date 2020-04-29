/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor.DomainRun
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.abstractions.{EquipmentManager, CarriageUnit, LoadAwareUnit}
import com.saldubatech.units.abstractions.CarriageUnit.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.units.carriage._
import com.saldubatech.util.LogEnabled

import scala.collection.{Set, mutable}

object LoadAwareShuttle {
	trait LoadShuttleSignal extends Identification

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with LoadShuttleSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with LoadShuttleSignal

	trait CommandForLoad extends ExternalCommand {
		val load: MaterialLoad
	}
	trait InboundCommand extends CommandForLoad {
	}
	trait OutboundCommand extends ExternalCommand {
		val to: String
	}
	case class Store(override val load: MaterialLoad, to: SlotLocator) extends InboundCommand
	case class Retrieve(override val load: MaterialLoad, override val to: String) extends CommandForLoad with OutboundCommand
	case class LoopBack(override val load: MaterialLoad, override val to: String) extends InboundCommand with OutboundCommand
	case class Groom(override val load: MaterialLoad, to: SlotLocator) extends CommandForLoad
	sealed trait RecoveryCommand extends ExternalCommand
	case class PutawayFromTray(to: SlotLocator) extends RecoveryCommand
	case class DeliverFromTray(override val to: String) extends RecoveryCommand with OutboundCommand

	sealed abstract class Notification extends Identification.Impl with EquipmentManager.Notification
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Notification
	case class FailedBusy(cmd: ExternalCommand, reason: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Notification
	case class MaxCommandsReached(cmd: ExternalCommand) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	case class Configuration[UpstreamMessage >: ChannelConnections.ChannelSourceMessage, DownStreamMessage >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 maxCommandsQueued: Int,
	 depth: Int,
	 physics: CarriageTravel,
	 inbound: Seq[Channel.Ops[MaterialLoad, UpstreamMessage, LoadShuttleSignal]],
	 outbound: Seq[Channel.Ops[MaterialLoad, LoadShuttleSignal, DownStreamMessage]])

	case class InitialState(position: Int, inventory: Map[SlotLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(configuration: Configuration[UpstreamMessageType, DownstreamMessageType], maxPendingCommands: Int,
	 initial: InitialState)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val domain = new LoadAwareShuttle(configuration.name, configuration, maxPendingCommands, initial)
		new Processor[LoadShuttleSignal](configuration.name, clockRef, simController, domain.configurer)
	}
}

class LoadAwareShuttle[UpstreamSignal >: ChannelConnections.ChannelSourceMessage, DownstreamSignal >: ChannelConnections.ChannelDestinationMessage]
(override val name: String,
 configuration: LoadAwareShuttle.Configuration[UpstreamSignal, DownstreamSignal],
 override val maxPendingCommands: Int,
 initial: LoadAwareShuttle.InitialState) extends Identification.Impl(name) with LoadAwareUnit[LoadAwareShuttle.LoadShuttleSignal] with LogEnabled {
	import LoadAwareShuttle._

	sealed trait CarriageSignal extends LoadShuttleSignal
	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductCmd[LoadShuttleSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends DischargeCmd[LoadShuttleSignal](to, at) with CarriageSignal

	override type HOST = LoadAwareShuttle[UpstreamSignal, DownstreamSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
	override type PRIORITY_COMMAND = RecoveryCommand
	override type NOTIFICATION = Notification
	override type LOAD_SIGNAL = Load
	override type UNLOAD_SIGNAL = Unload
	override type INDUCT_SIGNAL = Induct
	override type DISCHARGE_SIGNAL = Discharge

	override def loader(loc: SlotLocator) = Load(loc)
	override def unloader(loc: SlotLocator) = Unload(loc)
	override def inducter(from: INDUCT, at: SlotLocator) = Induct(from, at)
	override def discharger(to: DISCHARGE, at: SlotLocator) = Discharge(to, at)

	protected override def notAcceptedNotification(cmd: ExternalCommand, msg: String) = NotAcceptedCommand(cmd, msg)
	protected override def completedCommandNotification(cmd: ExternalCommand) = CompletedCommand(cmd)
	private def failFullNotification(cmd: ExternalCommand, msg: String) = FailedBusy(cmd, msg)
	private def failEmptyNotification(cmd: ExternalCommand, msg: String) = FailedEmpty(cmd, msg)

	protected override def maxCommandsReached(cmd: ExternalCommand) = MaxCommandsReached(cmd)

	val loadArrivalBehavior: (INDUCT, MaterialLoad, Option[Distance], CTX) => PartialFunction[CarriageUnit.WaitForLoad, RUNNER] =
		(induct: INDUCT, load: MaterialLoad, idx: Option[Distance], ctx: CTX) => {
			case NoLoadWait =>
				ctx.signal(manager, LoadArrival(induct.channelName, load))
				DomainRun.same
			case WaitInductingToDischarge(ep, toLoc, from) if from.channelName == induct.channelName =>
				carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
				endLoadWait
				channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducing(from, ep, toLoc))
			case WaitInductingToStore(loc, from) if from.channelName == induct.channelName =>
				carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
				endLoadWait
				channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(from, loc))
		}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[CarriageUnit.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait => Processor.DomainRun.same
			case WaitDischarging(ch, loc) =>
				carriageComponent.dischargeTo(ch, loc)(ctx)
				channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
		}
	}

	private val carriageComponent: CarriageComponent[LoadShuttleSignal, HOST] =
		new CarriageComponent[LoadShuttleSignal, HOST](configuration.physics, this).atLocation(initial.position).withInventory(initial.inventory)

//	private var currentCommand: Option[ExternalCommand] = None

	private val inboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, INDUCT] = mutable.Map.empty
	private var inboundLoadListener: RUNNER = _

	private val outboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, DISCHARGE] = mutable.Map.empty
	private var outboundAckListener: RUNNER = _

	private def isLoadAvailable(ld: MaterialLoad) = inboundChannels.values.find(induct => induct.peekNext.contains(ld))

	private def configurer: Processor.DomainConfigure[LoadShuttleSignal] = {
		new Processor.DomainConfigure[LoadShuttleSignal] {
			override def configure(config: LoadShuttleSignal)(implicit ctx: CTX): Processor.DomainMessageProcessor[LoadShuttleSignal] = {
				config match {
					case cmd@NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundSlots ++= configuration.inbound.zip(-configuration.inbound.size until 0).map(t => t._1.ch.name -> OnLeft(t._2))
						inboundChannels ++= configuration.inbound.map{chOps =>	chOps.ch.name -> CarriageUnit.inductSink[LoadShuttleSignal, HOST](LoadAwareShuttle.this)(loadArrivalBehavior)(inboundSlots(chOps.ch.name), chOps)}
						inboundLoadListener = configuration.inbound.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outbound.zip(-configuration.outbound.size until 0).map{c => c._1.ch.name -> OnRight(c._2)}
						outboundChannels ++= configuration.outbound.map{chOps => chOps.ch.name -> CarriageUnit.dischargeSource[LoadShuttleSignal, HOST](LoadAwareShuttle.this)(outboundSlots(chOps.ch.name), manager, chOps)(channelFreeBehavior)}
						outboundAckListener = configuration.outbound.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.configureContext.reply(CompletedConfiguration(ctx.aCtx.self))
						wrapBehavior(IDLE_BEHAVIOR)
				}
			}
		}
	}
	private lazy val channelListener = inboundLoadListener orElse outboundAckListener

	private def verifyLocator(l: SlotLocator): Option[SlotLocator] = if(l.idx < configuration.depth && l.idx >= 0) Some(l) else None

	override lazy val IDLE_BEHAVIOR: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@Store(load, toLocator) =>
				(isLoadAvailable(load), verifyLocator(toLocator)) match {
					case (Some(induct), Some(toLoc)) =>
						if (carriageComponent.inspect(toLoc) isEmpty) {
							carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))
							channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(induct, toLoc))
						} else completeCommand(IDLE_BEHAVIOR, FailedEmpty(_,s"Target Location to Store ($toLoc) is Full"))
					case (None, _) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Load($load) not available in induct"))
					case (_, None) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Destination $toLocator does not exist"))
					case other => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Load($load) and Destination ($other) are incompatible for Store Command: $cmd"))
				}
			case cmd@Retrieve(load,toCh) =>
				(carriageComponent.whereIs(load), outboundChannels.get(toCh)) match {
					case (Some(loc), Some(discharge)) =>
						if(carriageComponent.inspect(loc) isEmpty) completeCommand(IDLE_BEHAVIOR, FailedEmpty(_,s"Source Location ($loc) to Retrieve is Empty"))
						else {
							carriageComponent.loadFrom(loc)
							channelListener orElse carriageComponent.LOADING(dischargeAfterLoading(discharge, outboundSlots(toCh)))
						}
					case (None, _) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Load $load not in Storage"))
					case (_, None) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Destination $toCh does not exist"))
					case other => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"From or To ($other) are incompatible for Retrieve Command: $cmd"))
				}
			case cmd@Groom(load, toLocator) =>
				(carriageComponent.whereIs(load).flatMap(verifyLocator), verifyLocator(toLocator)) match {
					case (Some(from), Some(to)) =>
						if(carriageComponent.inspect(to) nonEmpty) completeCommand(IDLE_BEHAVIOR, FailedEmpty(_,s"Target Location to Store ($to) is not empty"))
						else if (carriageComponent.inspect(from) isEmpty) completeCommand(IDLE_BEHAVIOR, FailedEmpty(_,s"Source Location ($from) to Retrieve is Empty"))
						else {
							carriageComponent.loadFrom(from)
							channelListener orElse carriageComponent.LOADING(unloadAfterLoading(to))
						}
					case (None, _) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Load($load) not in storage"))
					case (_, None) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Destination $toLocator does not exist"))
					case other => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"From or To ($other) are incompatible for Groom Command: $cmd"))
				}
			case cmd@LoopBack(load, toCh) =>
				(isLoadAvailable(load), outboundChannels.get(toCh)) match {
					case (Some(induct), Some(to)) =>
						carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))
						channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducing(induct, to, outboundSlots(toCh)))
					case (None, _) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Load($load) not available in induct"))
					case (_, None) => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Destination $toCh does not exist"))
					case other => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"From or To ($other) are incompatible for Retrieve Command: $cmd"))
				}
			case cmd: ExternalCommand => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Unknown Command $cmd"))
		}
	}
	private lazy val PROCESS_FROM_FULL: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@PutawayFromTray(toLocator) =>
				verifyLocator(toLocator) match {
					case Some(toLoc) =>
						carriageComponent.unloadTo(toLoc)
						channelListener orElse carriageComponent.UNLOADING(afterUnloading)
					case None => completeCommand(PROCESS_FROM_FULL, failFullNotification(_, s"Destination $toLocator does not exist"))
				}
			case cmd@DeliverFromTray(chName) =>
				outboundChannels.get(chName) match {
					case Some(discharge) =>
						carriageComponent.dischargeTo(discharge, outboundSlots(chName))
						channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(discharge, outboundSlots(chName)))
					case None => completeCommand(PROCESS_FROM_FULL, failFullNotification(_, s"Outbound Channel $chName does not exist"))
				}
			case cmd: ExternalCommand => completeCommand(IDLE_BEHAVIOR, notAcceptedNotification(_, s"Unexpected Command: $cmd"))
		}
	}
	private def dischargeAfterLoading(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(PROCESS_FROM_FULL, failFullNotification(_,s"Trying to load to a full Tray"))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => completeCommand(IDLE_BEHAVIOR, failEmptyNotification(_,s"Trying to load from an empty Source"))
		}
	}
	private def unloadAfterLoading(loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				carriageComponent.UNLOADING(afterUnloading) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(PROCESS_FROM_FULL, failFullNotification(_,s"Trying to load to a full Tray"))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => completeCommand(IDLE_BEHAVIOR, failEmptyNotification(_, "Trying to load from an empty Source"))
		}
	}
	private def storeAfterInducting(from: INDUCT, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				carriageComponent.UNLOADING(afterUnloading) orElse channelListener
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToStore(loc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(PROCESS_FROM_FULL, failFullNotification(_,s"Trying to load to a full Tray"))
		}
	}
	private def dischargeAfterInducing(from: INDUCT, ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToDischarge(ch, loc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(PROCESS_FROM_FULL, failFullNotification(_,s"Trying to load to a full Tray"))
		}
	}

	private def afterUnloading: CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				waitInducting(inboundChannels.values.toSeq:_*)
				completeCommand(IDLE_BEHAVIOR)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				endLoadWait
				completeCommand(PROCESS_FROM_FULL, failFullNotification(_, s"Target destination is Full"))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty =>
				waitInducting(inboundChannels.values.toSeq:_*)
				completeCommand(IDLE_BEHAVIOR, failEmptyNotification(_, "Trying to unload an empty Tray"))
		}
	}

	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(IDLE_BEHAVIOR)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => completeCommand(IDLE_BEHAVIOR, failEmptyNotification(_, "Trying to unload an empty Tray"))
		}
	}
}
