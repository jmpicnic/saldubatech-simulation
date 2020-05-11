/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import com.saldubatech
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor.DomainRun
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.abstractions.{EquipmentManager, InductDischargeUnit, LoadAwareUnit}
import com.saldubatech.units.carriage._
import com.saldubatech.util.LogEnabled

import scala.collection.{Set, mutable}

object LoadAwareShuttle {
	trait LoadAwareShuttleSignal extends Identification

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with LoadAwareShuttleSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with LoadAwareShuttleSignal

	trait InboundCommand extends ExternalCommand {
		val load: MaterialLoad
	}
	trait OutboundCommand extends ExternalCommand {
		val to: String
	}
	case class Store(override val load: MaterialLoad, to: SlotLocator) extends InboundCommand
	case class Retrieve(load: MaterialLoad, to: String) extends OutboundCommand
	case class LoopBack(override val load: MaterialLoad, override val to: String) extends InboundCommand with OutboundCommand
	case class Groom(load: MaterialLoad, to: SlotLocator) extends ExternalCommand
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

	sealed trait InternalSignal extends LoadAwareShuttleSignal
	case class Execute(cmd: ExternalCommand) extends Identification.Impl() with InternalSignal

	trait AfferentChannel extends Channel.Afferent[MaterialLoad, LoadAwareShuttleSignal] { self =>
		override type TransferSignal = Channel.TransferLoad[MaterialLoad] with LoadAwareShuttleSignal
		override type PullSignal = Channel.PulledLoad[MaterialLoad] with LoadAwareShuttleSignal
		override type DeliverSignal = Channel.DeliverLoad[MaterialLoad] with LoadAwareShuttleSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareShuttleSignal
		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Distance) = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with LoadAwareShuttleSignal
		override def deliverBuilder(channel: String) = new Channel.DeliverLoadImpl[MaterialLoad](channel) with LoadAwareShuttleSignal
	}

	trait EfferentChannel extends Channel.Efferent[MaterialLoad, LoadAwareShuttleSignal] {
		override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with LoadAwareShuttleSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareShuttleSignal
	}

	case class Configuration[UpstreamMessage >: ChannelConnections.ChannelSourceMessage, DownStreamMessage >: ChannelConnections.ChannelDestinationMessage]
	(maxCommandsQueued: Int,
	 depth: Int,
	 physics: CarriageTravel,
	 inbound: Seq[Channel.Ops[MaterialLoad, UpstreamMessage, LoadAwareShuttleSignal]],
	 outbound: Seq[Channel.Ops[MaterialLoad, LoadAwareShuttleSignal, DownStreamMessage]])

	case class InitialState(position: Int, inventory: Map[SlotLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
	 initial: InitialState)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val domain = new LoadAwareShuttle(name, configuration, initial)
		new Processor[LoadAwareShuttleSignal](name, clockRef, simController, domain.configurer)
	}
}

class LoadAwareShuttle[UpstreamSignal >: ChannelConnections.ChannelSourceMessage, DownstreamSignal >: ChannelConnections.ChannelDestinationMessage]
(override val name: String,
 configuration: LoadAwareShuttle.Configuration[UpstreamSignal, DownstreamSignal],
 initial: LoadAwareShuttle.InitialState) extends Identification.Impl(name) with LoadAwareUnit[LoadAwareShuttle.LoadAwareShuttleSignal] with LogEnabled {

	import LoadAwareShuttle._

	sealed trait CarriageSignal extends LoadAwareShuttleSignal
	case class Load(override val loc: SlotLocator) extends InductDischargeUnit.LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends InductDischargeUnit.UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductDischargeUnit.InductCmd[LoadAwareShuttleSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends InductDischargeUnit.DischargeCmd[LoadAwareShuttleSignal](to, at) with CarriageSignal

	override type HOST = LoadAwareShuttle[UpstreamSignal, DownstreamSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
	override type PRIORITY_COMMAND = RecoveryCommand
	override type INBOUND_LOAD_COMMAND = InboundCommand
	override type NOTIFICATION = Notification
	override type LOAD_SIGNAL = Load
	override type UNLOAD_SIGNAL = Unload
	override type INDUCT_SIGNAL = Induct
	override type DISCHARGE_SIGNAL = Discharge

	override def execSignal(cmd: ExternalCommand) = Execute(cmd)
	override def loader(loc: SlotLocator) = Load(loc)
	override def unloader(loc: SlotLocator) = Unload(loc)
	override def inducter(from: INDUCT, at: SlotLocator) = Induct(from, at)
	override def discharger(to: DISCHARGE, at: SlotLocator) = Discharge(to, at)
	private def notAcceptedNotification(cmd: ExternalCommand, msg: String) = NotAcceptedCommand(cmd, msg)
	private def completedCommandNotification(cmd: ExternalCommand) = CompletedCommand(cmd)
	private def failFullNotification(cmd: ExternalCommand, msg: String) = FailedBusy(cmd, msg)
	private def failEmptyNotification(cmd: ExternalCommand, msg: String) = FailedEmpty(cmd, msg)
	protected override def maxCommandsReached(cmd: ExternalCommand) = MaxCommandsReached(cmd)
	protected override val maxPendingCommands: Int = configuration.maxCommandsQueued
	protected override def loadArrival(chName: String, ld: MaterialLoad) =  LoadArrival(chName, ld)

	val loadArrivalBehavior: (INDUCT, MaterialLoad, Option[Distance], CTX) => Function1[InductDischargeUnit.WaitForLoad, RUNNER] =
		(induct: INDUCT, load: MaterialLoad, idx: Option[Distance], ctx: CTX) => { wflState =>
			implicit val iCtx = ctx
			gotLoad(ctx.now -> load, induct.channelName)
			wflState match {
				case NoLoadWait =>
					triggerNext(DomainRun.same)
				case WaitInductingToDischarge(ep, toLoc, from) if from.channelName == induct.channelName =>
					carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
					endLoadWait
					triggerNext(continueCommand(channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducing(from, ep, toLoc))))
				case WaitInductingToStore(loc, from) if from.channelName == induct.channelName =>
					carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
					endLoadWait
					triggerNext(continueCommand(channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(from, loc))))
			}
		}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[InductDischargeUnit.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait =>
				implicit val iCtx = ctx
				triggerNext(Processor.DomainRun.same)
			case WaitDischarging(ch, loc) =>
				implicit val iCtx = ctx
				carriageComponent.dischargeTo(ch, loc)(ctx)
				triggerNext(continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))))
		}
	}

	private val carriageComponent: CarriageComponent[LoadAwareShuttleSignal, HOST] =
		new CarriageComponent[LoadAwareShuttleSignal, HOST](configuration.physics, this).atLocation(initial.position).withInventory(initial.inventory)

	private val inboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, INDUCT] = mutable.Map.empty
	private var inboundLoadListener: RUNNER = _

	private val outboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, DISCHARGE] = mutable.Map.empty
	private var outboundAckListener: RUNNER = _

	private def isLoadAvailable(ld: MaterialLoad) = inboundChannels.values.find(induct => induct.peekNext.exists(_._1 == ld))

	private def configurer: Processor.DomainConfigure[LoadAwareShuttleSignal] = {
		new Processor.DomainConfigure[LoadAwareShuttleSignal] {
			override def configure(config: LoadAwareShuttleSignal)(implicit ctx: CTX): Processor.DomainMessageProcessor[LoadAwareShuttleSignal] = {
				config match {
					case cmd@NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundSlots ++= configuration.inbound.zip(-configuration.inbound.size until 0).map(t => t._1.ch.name -> OnLeft(t._2))
						inboundChannels ++= configuration.inbound.map { chOps => chOps.ch.name -> InductDischargeUnit.inductSink[LoadAwareShuttleSignal, HOST](LoadAwareShuttle.this)(loadArrivalBehavior)(inboundSlots(chOps.ch.name), chOps) }
						inboundLoadListener = configuration.inbound.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outbound.zip(-configuration.outbound.size until 0).map { c => c._1.ch.name -> OnRight(c._2) }
						outboundChannels ++= configuration.outbound.map { chOps => chOps.ch.name -> InductDischargeUnit.dischargeSource[LoadAwareShuttleSignal, HOST](LoadAwareShuttle.this)(outboundSlots(chOps.ch.name), manager, chOps)(channelFreeBehavior) }
						outboundAckListener = configuration.outbound.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.configureContext.reply(CompletedConfiguration(ctx.aCtx.self))
						idleExecutor
				}
			}
		}
	}


	private lazy val idleExecutor: RUNNER = continueCommand(idleListener, emptyCmd)
	private lazy val trayFullExecutor2: RUNNER = continueCommand(trayFullListener, fullCmd)

	private lazy val channelListener = inboundLoadListener orElse outboundAckListener
	private lazy val idleListener: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case Execute(cmd) => processCmd(ctx)(cmd)
		}
	}
	private lazy val trayFullListener: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case Execute(cmd: RecoveryCommand) => processRecovery(ctx)(cmd)
			case Execute(cmd) => processCmd(ctx)(cmd)
		}
	}
	private def fullCmd(cmd: ExternalCommand) = cmd match {
		case _: RecoveryCommand => true
		case _ => false
	}
	private def emptyCmd(cmd: ExternalCommand) = !fullCmd(cmd)

	private def verifyLocator(l: SlotLocator): Option[SlotLocator] = if (l.idx < configuration.depth && l.idx >= 0) Some(l) else None

	def processCmd(implicit ctx: CTX): PartialFunction[ExternalCommand, RUNNER] = {
		case cmd@Store(load, toLocator) =>
			(isLoadAvailable(load), verifyLocator(toLocator)) match {
				case (Some(induct), Some(toLoc)) =>
					if (carriageComponent.inspect(toLoc) isEmpty) {
						carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))
						continueCommand(channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(induct, toLoc)))
					} else completeCommand(idleListener, FailedEmpty(_, s"Target Location to Store ($toLoc) is Full"))
				case (None, _) => completeCommand(idleListener, notAcceptedNotification(_, s"Load($load) not available in induct"))
				case (_, None) => completeCommand(idleListener, notAcceptedNotification(_, s"Destination $toLocator does not exist"))
				case other => completeCommand(idleListener, notAcceptedNotification(_, s"Load($load) and Destination ($other) are incompatible for Store Command: $cmd"))
			}
		case cmd@Retrieve(load, toCh) =>
			(carriageComponent.whereIs(load), outboundChannels.get(toCh)) match {
				case (Some(loc), Some(discharge)) =>
					if (carriageComponent.inspect(loc) isEmpty) completeCommand(idleListener, FailedEmpty(_, s"Source Location ($loc) to Retrieve is Empty"))
					else {
						carriageComponent.loadFrom(loc)
						continueCommand(channelListener orElse carriageComponent.LOADING(dischargeAfterLoading(discharge, outboundSlots(toCh))))
					}
				case (None, _) => completeCommand(idleListener, notAcceptedNotification(_, s"Load $load not in Storage"))
				case (_, None) => completeCommand(idleListener, notAcceptedNotification(_, s"Destination $toCh does not exist"))
				case other => completeCommand(idleListener, notAcceptedNotification(_, s"From or To ($other) are incompatible for Retrieve Command: $cmd"))
			}
		case cmd@Groom(load, toLocator) =>
			(carriageComponent.whereIs(load).flatMap(verifyLocator), verifyLocator(toLocator)) match {
				case (Some(from), Some(to)) =>
					if (carriageComponent.inspect(to) nonEmpty) completeCommand(idleListener, FailedEmpty(_, s"Target Location to Store ($to) is not empty"))
					else if (carriageComponent.inspect(from) isEmpty) completeCommand(idleListener, FailedEmpty(_, s"Source Location ($from) to Retrieve is Empty"))
					else {
						carriageComponent.loadFrom(from)
						continueCommand(channelListener orElse carriageComponent.LOADING(unloadAfterLoading(to)))
					}
				case (None, _) => completeCommand(idleListener, notAcceptedNotification(_, s"Load($load) not in storage"))
				case (_, None) => completeCommand(idleListener, notAcceptedNotification(_, s"Destination $toLocator does not exist"))
				case other => completeCommand(idleListener, notAcceptedNotification(_, s"From or To ($other) are incompatible for Groom Command: $cmd"))
			}
		case cmd@LoopBack(load, toCh) =>
			(isLoadAvailable(load), outboundChannels.get(toCh)) match {
				case (Some(induct), Some(to)) =>
					carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))
					continueCommand(channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducing(induct, to, outboundSlots(toCh))))
				case (None, _) => completeCommand(idleListener, notAcceptedNotification(_, s"Load($load) not available in induct"))
				case (_, None) => completeCommand(idleListener, notAcceptedNotification(_, s"Destination $toCh does not exist"))
				case other => completeCommand(idleListener, notAcceptedNotification(_, s"From or To ($other) are incompatible for Retrieve Command: $cmd"))
			}
	}

	private lazy val processRecovery: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@PutawayFromTray(toLocator) =>
				verifyLocator(toLocator) match {
					case Some(toLoc) =>
						carriageComponent.unloadTo(toLoc)
						continueCommand(channelListener orElse carriageComponent.UNLOADING(afterUnloading))
					case None => completeCommand(trayFullListener, failFullNotification(_, s"Destination $toLocator does not exist"))
				}
			case cmd@DeliverFromTray(chName) =>
				outboundChannels.get(chName) match {
					case Some(discharge) =>
						carriageComponent.dischargeTo(discharge, outboundSlots(chName))
						continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(discharge, outboundSlots(chName))))
					case None => completeCommand(trayFullListener, failFullNotification(_, s"Outbound Channel $chName does not exist"))
				}
			case cmd: ExternalCommand => completeCommand(idleListener, notAcceptedNotification(_, s"Unexpected Command: $cmd"))
		}
	}
	private def dischargeAfterLoading(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				continueCommand(carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)) orElse channelListener)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(trayFullListener, failFullNotification(_,s"Trying to load to a full Tray"))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => completeCommand(idleListener, failEmptyNotification(_,s"Trying to load from an empty Source"))
		}
	}
	private def unloadAfterLoading(loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				continueCommand(carriageComponent.UNLOADING(afterUnloading) orElse channelListener)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(trayFullListener, failFullNotification(_,s"Trying to load to a full Tray"))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => completeCommand(idleListener, failEmptyNotification(_, "Trying to load from an empty Source"))
		}
	}
	private def storeAfterInducting(from: INDUCT, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				continueCommand(carriageComponent.UNLOADING(afterUnloading) orElse channelListener)
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToStore(loc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(trayFullListener, failFullNotification(_,s"Trying to load to a full Tray"))
		}
	}
	private def dischargeAfterInducing(from: INDUCT, ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToDischarge(ch, loc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => completeCommand(trayFullListener, failFullNotification(_,s"Trying to load to a full Tray"))
		}
	}

	private def afterUnloading: CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded => completeCommand(idleListener, completedCommandNotification)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull => completeCommand(trayFullListener, failFullNotification(_, s"Target destination is Full"))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => completeCommand(idleListener, failEmptyNotification(_, "Trying to unload an empty Tray"))
		}
	}

	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(idleListener, completedCommandNotification)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => completeCommand(idleListener, failEmptyNotification(_, "Trying to unload an empty Tray"))
		}
	}
}
