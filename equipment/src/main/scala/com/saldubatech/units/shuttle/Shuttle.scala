/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate.{DomainConfigure, DomainMessageProcessor, DomainRun}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.ddes.{AgentTemplate, Clock, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.protocols.{Equipment, EquipmentManagement}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.abstractions.{CarriageUnit, InductDischargeUnit}
import com.saldubatech.units.carriage._
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object Shuttle {

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with Equipment.ShuttleSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with Equipment.ShuttleSignal
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

	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class FailedBusy(cmd: ExternalCommand, reason: String) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Identification.Impl() with EquipmentManagement.ShuttleNotification
	case class CompletedConfiguration(self: SimRef) extends Identification.Impl() with EquipmentManagement.ShuttleNotification

	case class Configuration[UpstreamMessage >: Equipment.ChannelSourceSignal <: DomainSignal, DownStreamMessage >: Equipment.ChannelSinkSignal <: DomainSignal]
	(name: String,
	 depth: Int,
	 physics: CarriageTravel,
	 inbound: Seq[Channel.Ops[MaterialLoad, UpstreamMessage, Equipment.ShuttleSignal]],
	 outbound: Seq[Channel.Ops[MaterialLoad, Equipment.ShuttleSignal, DownStreamMessage]])

	case class InitialState(position: Int, inventory: Map[SlotLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: Equipment.ChannelSourceSignal <: DomainSignal, DownstreamMessageType >: Equipment.ChannelSinkSignal <: DomainSignal]
	(configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
	 initial: InitialState)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val domain = new Shuttle(configuration.name, configuration, initial)
		new  AgentTemplate.Wrapper[Equipment.ShuttleSignal](configuration.name, clockRef, simController, domain.configurer)
	}
}

class Shuttle[UpstreamSignal >: Equipment.ChannelSourceSignal <: DomainSignal, DownstreamSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
(override val name: String,
 configuration: Shuttle.Configuration[UpstreamSignal, DownstreamSignal],
 initial: Shuttle.InitialState) extends Identification.Impl(name) with CarriageUnit[Equipment.ShuttleSignal] with InductDischargeUnit[Equipment.ShuttleSignal] with LogEnabled {
	import Shuttle._


	sealed trait CarriageSignal extends Equipment.ShuttleSignal
	case class Load(override val loc: SlotLocator) extends InductDischargeUnit.LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends InductDischargeUnit.UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductDischargeUnit.InductCmd[Equipment.ShuttleSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends InductDischargeUnit.DischargeCmd[Equipment.ShuttleSignal](to, at) with CarriageSignal

	override type HOST = Shuttle[UpstreamSignal, DownstreamSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
	override type NOTIFICATION = EquipmentManagement.ShuttleNotification
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

	val loadArrivalBehavior: (INDUCT, MaterialLoad, Option[Distance], CTX) => PartialFunction[InductDischargeUnit.WaitForLoad, RUNNER] =
		(induct: INDUCT, load: MaterialLoad, idx: Option[Distance], ctx: CTX) => {
		case NoLoadWait =>
			ctx.signal(manager, LoadArrival(induct.channelName, load))
			DomainRun.same
		case WaitInductingToDischarge(ep, toLoc, from) if from.channelName == induct.channelName =>
			carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
			endLoadWait
			busyGuard orElse channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(from, ep, toLoc))
		case WaitInductingToStore(loc, from) if from.channelName == induct.channelName =>
			carriageComponent.inductFrom(induct, inboundSlots(induct.channelName))(ctx)
			endLoadWait
			busyGuard orElse channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(from, loc))
	}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[InductDischargeUnit.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait => DomainRun.same
			case WaitDischarging(ch, loc) =>
				carriageComponent.dischargeTo(ch, loc)(ctx)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
		}
	}

	private val carriageComponent: CarriageComponent[Equipment.ShuttleSignal, HOST] =
		new CarriageComponent[Equipment.ShuttleSignal, HOST](configuration.physics, this).atLocation(initial.position).withInventory(initial.inventory)

//	private var currentCommand: Option[ExternalCommand] = None

	private val inboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, INDUCT] = mutable.Map.empty
	private var inboundLoadListener: RUNNER = _

	private val outboundSlots: mutable.Map[String, SlotLocator] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, DISCHARGE] = mutable.Map.empty
	private var outboundAckListener: RUNNER = _

	private def configurer: DomainConfigure[Equipment.ShuttleSignal] = {
		new DomainConfigure[Equipment.ShuttleSignal] {
			override def configure(config: Equipment.ShuttleSignal)(implicit ctx: CTX): DomainMessageProcessor[Equipment.ShuttleSignal] = {
				config match {
					case cmd@NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundSlots ++= configuration.inbound.zip(-configuration.inbound.size until 0).map(t => t._1.ch.name -> OnLeft(t._2))
						inboundChannels ++= configuration.inbound.map{chOps =>	chOps.ch.name -> InductDischargeUnit.inductSink[Equipment.ShuttleSignal, HOST](Shuttle.this)(loadArrivalBehavior)(inboundSlots(chOps.ch.name), chOps)}
						inboundLoadListener = configuration.inbound.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outbound.zip(-configuration.outbound.size until 0).map{c => c._1.ch.name -> OnRight(c._2)}
						outboundChannels ++= configuration.outbound.map{chOps => chOps.ch.name -> InductDischargeUnit.dischargeSource[Equipment.ShuttleSignal, HOST](Shuttle.this)(outboundSlots(chOps.ch.name), manager, chOps)(channelFreeBehavior)}
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
			case cmd: ExternalCommand => rejectExternalCommand(cmd, s"Shuttle($name) is busy")
		}
	}
	private def verifyLocator(l: SlotLocator): Option[SlotLocator] = if(l.idx < configuration.depth && l.idx >= 0) Some(l) else None


	private lazy val IDLE_EMPTY: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd@Store(fromCh, toLocator) => executeCommand(cmd){
				(inboundChannels.get(fromCh), verifyLocator(toLocator)) match {
					case (Some(induct), Some(toLoc)) =>
						if(carriageComponent.inspect(toLoc) nonEmpty) failEmpty(s"Target Location to Store ($toLoc) is Full")
						else {
							carriageComponent.inductFrom(induct, inboundSlots(fromCh))
							channelListener orElse carriageComponent.INDUCTING(storeAfterInducting(induct, toLoc))
						}
					case (None, _) =>
						doneCommand
						rejectExternalCommand(cmd, s"Inbound Channel does not exist")
					case (_, None) =>
						doneCommand
						rejectExternalCommand(cmd, s"Destination $toLocator does not exist")
					case other =>
						doneCommand
						rejectExternalCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
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
						doneCommand
						rejectExternalCommand(cmd, s"Inbound Source $fromLocator does not exist")
					case (_, None) =>
						doneCommand
						rejectExternalCommand(cmd, "Destination $toCh does not exist")
					case other =>
						doneCommand
						rejectExternalCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
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
						doneCommand
						rejectExternalCommand(cmd, s"Source location does not exist")
					case (_, None) =>
						doneCommand
						rejectExternalCommand(cmd, "Destination does not exist")
					case other =>
						doneCommand
						rejectExternalCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd@LoopBack(fromChName, toChName) => executeCommand(cmd){
				(inboundChannels.get(fromChName), outboundChannels.get(toChName)) match {
					case (Some(from), Some(to)) =>
						carriageComponent.inductFrom(from, inboundSlots(fromChName))
						channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(from, to, outboundSlots(toChName)))
					case (None, _) =>
						doneCommand
						rejectExternalCommand(cmd, s"Inbound Channel does not exist")
					case (_, None) =>
						doneCommand
						rejectExternalCommand(cmd, "Destination does not exist")
					case other =>
						doneCommand
						rejectExternalCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd")
				}
			}
			case cmd: ExternalCommand => rejectExternalCommand(cmd, "Unknown Command $cmd")
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
			case cmd: ExternalCommand => rejectExternalCommand(cmd, s"Unexpected Command: $cmd")
		}
	}
	private def dischargeAfterLoading(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => failEmpty("Trying to load from an empty Source")
		}
	}
	private def unloadAfterLoading(loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.unloadTo(loc)
				carriageComponent.UNLOADING(afterUnloading) orElse channelListener
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty => failEmpty("Trying to load from an empty Source")
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
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
		}
	}
	private def completeInductingAndDischarge(from: INDUCT, ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				carriageComponent.dischargeTo(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToDischarge(ch, loc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => failFull(s"Trying to load to a full Tray")
		}
	}

	private def afterUnloading: CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded => completeCommand(IDLE_EMPTY)
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull => failFull(s"Target destination is Full")
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => failEmpty("Trying to unload an empty Tray")
		}
	}

	private def failFull(msg: String)(implicit ctx: CTX): RUNNER = completeCommand(IDLE_EMPTY, cmd => FailedBusy(cmd, msg))

	private def failEmpty(msg: String)(implicit ctx: CTX): RUNNER = completeCommand(IDLE_EMPTY,  cmd => FailedEmpty(cmd, msg))

	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(IDLE_EMPTY)
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}
}
