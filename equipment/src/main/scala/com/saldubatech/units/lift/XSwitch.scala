/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate.{DomainConfigure, DomainMessageProcessor, DomainRun}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.ddes.{AgentTemplate, Clock, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.protocols.Equipment.XSwitchSignal
import com.saldubatech.protocols.{Equipment, EquipmentManagement, MaterialLoad}
import com.saldubatech.transport.Channel
import com.saldubatech.units.abstractions.InductDischargeUnit.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.units.abstractions.{CarriageUnit, InductDischargeUnit}
import com.saldubatech.units.carriage.{At, CarriageComponent, CarriageTravel, SlotLocator}
import com.saldubatech.util.LogEnabled


object XSwitch {

	sealed abstract class ConfigurationCommand extends Identification.Impl() with Equipment.XSwitchSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with Equipment.XSwitchSignal
	case class Transfer(fromCh: String, toCh: String) extends ExternalCommand

	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class FailedBusy(cmd: ExternalCommand, msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class FailedWaiting(msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class CompletedConfiguration(self: SimRef[XSwitchSignal]) extends Identification.Impl() with EquipmentManagement.XSwitchNotification

	case class Configuration[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
		OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
	(physics: CarriageTravel,
	 inboundInduction: Map[Int, Channel.Ops[MaterialLoad, InboundInductSignal, Equipment.XSwitchSignal]],
	 inboundDischarge: Map[Int, Channel.Ops[MaterialLoad, Equipment.XSwitchSignal, InboundDischargeSignal]],
	 outboundInduction: Map[Int, Channel.Ops[MaterialLoad, OutboundInductSignal, Equipment.XSwitchSignal]],
	 outboundDischarge: Map[Int, Channel.Ops[MaterialLoad, Equipment.XSwitchSignal, OutboundDischargeSignal]],
	 initialAlignment: Int
	)

	def buildProcessor[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
		OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
	(name: String, configuration: Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		new  AgentTemplate.Wrapper[Equipment.XSwitchSignal](name, clockRef, simController, new XSwitch(name, configuration).configurer)
	}
}

class XSwitch[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
	OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
(override val name: String, configuration: XSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	extends Identification.Impl(name) with CarriageUnit[Equipment.XSwitchSignal] with InductDischargeUnit[Equipment.XSwitchSignal] with LogEnabled {
	import XSwitch._

	sealed trait CarriageSignal extends Equipment.XSwitchSignal
	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductCmd[Equipment.XSwitchSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends DischargeCmd[Equipment.XSwitchSignal](to, at) with CarriageSignal

	override type HOST = XSwitch[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
	override type NOTIFICATION = EquipmentManagement.XSwitchNotification
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
			case WaitInductingToDischarge(to, toLoc, from) if from.channelName == induct.channelName =>
				val loc =
					(inboundRouting.inductByName(induct.channelName) orElse outboundRouting.inductByName(induct.channelName)).map(t => At(t._1))
				if(loc isEmpty) throw new RuntimeException(s"Undefined induct ${induct.channelName} for XSwitch($name)")
				loc.foreach(carriageComponent.inductFrom(induct, _)(ctx))
				busyGuard orElse channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(from, to, toLoc))
			case w: WaitInductingToDischarge =>
				completeCommand(IDLE,
					_ => FailedWaiting(s"Received On Unexpected Channel: ${induct.channelName} while waiting on ${w.discharge.channelName} for command $currentCommand"))(ctx)
		}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[InductDischargeUnit.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait => DomainRun.same
			case WaitDischarging(ch, loc) =>
				carriageComponent.dischargeTo(ch, loc)(ctx)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
		}
	}

	private val carriageComponent: CarriageComponent[Equipment.XSwitchSignal, HOST] =
		new CarriageComponent[Equipment.XSwitchSignal, HOST](configuration.physics, this).atLocation(configuration.initialAlignment)

	private case class RoutingGroup(inducts: Map[Int, INDUCT], discharges: Map[Int, DISCHARGE]) {
		private val inductsByName = inducts.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		private val dischargesByName = discharges.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		lazy val loadListener: RUNNER = if(inducts isEmpty) DomainRun.noOp else inducts.values.map(_.loadReceiver).reduce((l,r) => l orElse r)
		lazy val ackListener: RUNNER = if(discharges isEmpty) DomainRun.noOp else discharges.values.map(_.ackReceiver).reduce((l,r) => l orElse r)
		def inductByLoc(loc: SlotLocator) = inducts.get(loc.idx)
		def dischargeByLoc(loc: SlotLocator) = discharges.get(loc.idx)
		def inductByName(chName: String) = inductsByName.get(chName)
		def dischargeByName(chName: String) = dischargesByName.get(chName)
		def route(from: SlotLocator, to: SlotLocator): Option[(INDUCT, DISCHARGE)] =
			for{
				induct <- inductByLoc(from)
				discharge <- dischargeByLoc(to)
			} yield induct -> discharge

		def route(from: String, to: String): Option[((Int, INDUCT), (Int, DISCHARGE))] =
			for{
				induct <- inductByName(from)
				discharge <- dischargeByName(to)
			} yield induct -> discharge
	}
	private var inboundRouting: RoutingGroup = _
	private var outboundRouting: RoutingGroup = _


	private def configurer: DomainConfigure[Equipment.XSwitchSignal] = {
		new DomainConfigure[Equipment.XSwitchSignal] {
			override def configure(config: Equipment.XSwitchSignal)(implicit ctx: CTX): DomainMessageProcessor[Equipment.XSwitchSignal] = {
				config match {
					case XSwitch.NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundRouting = RoutingGroup(
							configuration.inboundInduction.map{
								case (idx, ch) => idx -> InductDischargeUnit.inductSink[Equipment.XSwitchSignal, HOST](XSwitch.this)(loadArrivalBehavior)(At(idx), ch)},
							configuration.inboundDischarge.map{case (idx, ch) => idx -> InductDischargeUnit.dischargeSource[Equipment.XSwitchSignal, HOST](XSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
						outboundRouting = RoutingGroup(
							configuration.outboundInduction.map{
								case (idx, ch) => idx -> InductDischargeUnit.inductSink[Equipment.XSwitchSignal, HOST](XSwitch.this)(loadArrivalBehavior)(At(idx),ch)},
							configuration.outboundDischarge.map{case (idx, ch) => idx -> InductDischargeUnit.dischargeSource[Equipment.XSwitchSignal, HOST](XSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
						ctx.configureContext.signal(manager, CompletedConfiguration(ctx.aCtx.self))
						IDLE
				}
			}
		}
	}
	private lazy val loadListener: RUNNER = inboundRouting.loadListener orElse outboundRouting.loadListener
	private lazy val ackListener: RUNNER = inboundRouting.ackListener orElse outboundRouting.ackListener
	private lazy val channelListener: RUNNER = loadListener orElse ackListener
	private lazy val busyGuard: RUNNER = {
		implicit ctx: CTX => {
			case cmd: ExternalCommand => rejectExternalCommand(cmd, s"XSwitch($name) is busy")
		}
	}



	private lazy val IDLE: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd @ Transfer(from, to) => executeCommand(cmd) {
				val route: Option[((At, INDUCT), (At, DISCHARGE))] =
					(inboundRouting.route(from, to) orElse outboundRouting.route(from, to)).map{case ((inductIdx, i), (dischargeIdx, d)) =>
						((At(inductIdx), i), (At(dischargeIdx), d))}
				route match {
					case Some(((inductLoc, induct), (dischargeLoc, discharge))) =>
						carriageComponent.inductFrom(induct, inductLoc)
						busyGuard orElse channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(induct, discharge, dischargeLoc))
					case None =>
						doneCommand
						rejectExternalCommand(cmd, s"Invalid Requested Routing: $from -> $to for XSwitch($name)")
				}
			}
		}
	}

	private def completeInductingAndDischarge(from: INDUCT, dischargeChannel: DISCHARGE, dischargeLoc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				endLoadWait
				carriageComponent.dischargeTo(dischargeChannel, dischargeLoc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(dischargeChannel, dischargeLoc))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToDischarge(dischargeChannel, dischargeLoc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => throw new RuntimeException(s"Carriage Failed Full while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}


	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(IDLE)
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}

}
