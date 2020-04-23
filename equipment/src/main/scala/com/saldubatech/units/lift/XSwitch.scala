/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.carriage.Host.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.units.carriage.{At, CarriageComponent, CarriageTravel, Host, SlotLocator}
import com.saldubatech.util.LogEnabled


object XSwitch {
	trait XSwitchSignal extends Identification

	sealed abstract class ConfigurationCommand extends Identification.Impl() with XSwitchSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with XSwitchSignal
	case class Transfer(fromCh: String, toCh: String) extends ExternalCommand

	sealed abstract class Notification extends Identification.Impl() with EquipmentManager.Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class FailedBusy(cmd: ExternalCommand, msg: String) extends Notification
	case class FailedWaiting(msg: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, msg: String) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	case class Configuration[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
		OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
	(physics: CarriageTravel,
	 inboundInduction: Map[Int, Channel.Ops[MaterialLoad, InboundInductSignal, XSwitchSignal]],
	 inboundDischarge: Map[Int, Channel.Ops[MaterialLoad, XSwitchSignal, InboundDischargeSignal]],
	 outboundInduction: Map[Int, Channel.Ops[MaterialLoad, OutboundInductSignal, XSwitchSignal]],
	 outboundDischarge: Map[Int, Channel.Ops[MaterialLoad, XSwitchSignal, OutboundDischargeSignal]],
	 initialAlignment: Int
	)

	def buildProcessor[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
		OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
	(name: String, configuration: Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		new Processor[XSwitch.XSwitchSignal](name, clockRef, simController, new XSwitch(name, configuration).configurer)
	}
}

class XSwitch[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
	OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
(override val name: String, configuration: XSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	extends Identification.Impl(name) with Host[XSwitch.XSwitchSignal] with LogEnabled {
	import XSwitch._

	sealed trait CarriageSignal extends XSwitchSignal
	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductCmd[XSwitchSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends DischargeCmd[XSwitchSignal](to, at) with CarriageSignal

	override type HOST = XSwitch[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
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

	val loadArrivalBehavior: (INDUCT, MaterialLoad, Option[Distance], CTX) => PartialFunction[Host.WaitForLoad, RUNNER] =
		(induct: INDUCT, load: MaterialLoad, idx: Option[Distance], ctx: CTX) => {
			case NoLoadWait =>
				ctx.signal(manager, LoadArrival(induct.channelName, load))
				Processor.DomainRun.same
			case WaitInductingToDischarge(from, to, toLoc) if (from.channelName == induct.channelName) =>
				val loc =
					(inboundRouting.inductByName(induct.channelName) orElse outboundRouting.inductByName(induct.channelName)).map(t => At(t._1))
				if(loc isEmpty) throw new RuntimeException(s"Undefined induct ${induct.channelName} for XSwitch($name)")
				loc.foreach(carriageComponent.inductFrom(induct, _)(ctx))
				busyGuard orElse channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(from, to, toLoc))
			case w: WaitInductingToDischarge =>
				completeCommand(IDLE,
					cmd => FailedWaiting(s"Received On Unexpected Channel: ${induct.channelName} while waiting on ${w.discharge.channelName} for command $currentCommand"))(ctx)
		}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[Host.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait => Processor.DomainRun.same
			case WaitDischarging(ch, loc) =>
				carriageComponent.dischargeTo(ch, loc)(ctx)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
		}
	}

	private val carriageComponent: CarriageComponent[XSwitchSignal, HOST] =
		new CarriageComponent[XSwitchSignal, HOST](configuration.physics, this).atLocation(configuration.initialAlignment)

	private case class RoutingGroup(inducts: Map[Int, INDUCT], discharges: Map[Int, DISCHARGE]) {
		private val inductsByName = inducts.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		private val dischargesByName = discharges.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		lazy val loadListener: RUNNER = if(inducts isEmpty) Processor.DomainRun.noOp else inducts.values.map(_.loadReceiver).reduce((l,r) => l orElse r)
		lazy val ackListener: RUNNER = if(discharges isEmpty) Processor.DomainRun.noOp else discharges.values.map(_.ackReceiver).reduce((l,r) => l orElse r)
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


	private def configurer: Processor.DomainConfigure[XSwitchSignal] = {
		new Processor.DomainConfigure[XSwitchSignal] {
			override def configure(config: XSwitchSignal)(implicit ctx: CTX): Processor.DomainMessageProcessor[XSwitchSignal] = {
				config match {
					case XSwitch.NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundRouting = new RoutingGroup(
							configuration.inboundInduction.map{
								case (idx, ch) => idx -> Host.inductSink[XSwitchSignal, HOST](XSwitch.this)(loadArrivalBehavior)(At(idx), ch)},
							configuration.inboundDischarge.map{case (idx, ch) => idx -> Host.dischargeSource[XSwitchSignal, HOST](XSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
						outboundRouting = new RoutingGroup(
							configuration.outboundInduction.map{
								case (idx, ch) => idx -> Host.inductSink[XSwitchSignal, HOST](XSwitch.this)(loadArrivalBehavior)(At(idx),ch)},
							configuration.outboundDischarge.map{case (idx, ch) => idx -> Host.dischargeSource[XSwitchSignal, HOST](XSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
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
				waitInductingToDischarge(from, dischargeChannel, dischargeLoc)
				Processor.DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => throw new RuntimeException(s"Carriage Failed Full while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}


	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(IDLE)
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}

}
