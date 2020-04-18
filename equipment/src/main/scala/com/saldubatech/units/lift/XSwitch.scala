/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor.DelayedDomainRun
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.carriage.{At, CarriageComponent, CarriageTravel, SlotLocator}
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
(name: String, configuration: XSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	extends Identification.Impl(name) with CarriageComponent.Host[XSwitch.XSwitchSignal, XSwitch.XSwitchSignal] with LogEnabled {
	import XSwitch._

	override type HOST_SIGNAL = XSwitchSignal

	sealed trait CarriageSignal extends XSwitchSignal
	case class Load(override val loc: SlotLocator) extends CarriageComponent.LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends CarriageComponent.UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: Channel.End[MaterialLoad, XSwitchSignal], override val at: SlotLocator)
		extends CarriageComponent.InductCmd[XSwitchSignal](from, at) with CarriageSignal
	case class Discharge(override val to: Channel.Start[MaterialLoad, XSwitchSignal], override val at: SlotLocator)
		extends CarriageComponent.DischargeCmd[XSwitchSignal](to, at) with CarriageSignal

	override type HOST = XSwitch[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal]
	override type LOAD_SIGNAL = Load
	override type UNLOAD_SIGNAL = Unload
	override type INDUCT_SIGNAL = Induct
	override type DISCHARGE_SIGNAL = Discharge

	override def loader(loc: SlotLocator) = Load(loc)
	override def unloader(loc: SlotLocator) = Unload(loc)
	override def inducter(from: Channel.End[MaterialLoad, XSwitchSignal], at: SlotLocator) = Induct(from, at)
	override def discharger(to: Channel.Start[MaterialLoad, XSwitchSignal], at: SlotLocator) = Discharge(to, at)

	private sealed trait WaitForLoad
	private case object NoLoadWait extends WaitForLoad
	private case class WaitInducting(ep: Channel.Start[MaterialLoad, XSwitchSignal], toLoc: SlotLocator) extends WaitForLoad

	private val carriageComponent: CarriageComponent[XSwitchSignal, XSwitchSignal, HOST] =
		new CarriageComponent[XSwitchSignal, XSwitchSignal, HOST](configuration.physics, this).atLocation(configuration.initialAlignment)

	private var waitingForLoad: WaitForLoad = NoLoadWait
	private var manager: Processor.Ref = _
	private var currentCommand: Option[ExternalCommand] = None

	private def inboundSink(chOps: Channel.Ops[MaterialLoad, _, XSwitchSignal])(implicit ctx: CTX): Channel.End[MaterialLoad, XSwitchSignal] =
		new Channel.Sink[MaterialLoad, XSwitchSignal] {
			override val ref: Processor.Ref = ctx.aCtx.self

			override def loadArrived(fromEp: Channel.End[MaterialLoad, XSwitchSignal], ld: MaterialLoad, at: Option[Distance] = None)(implicit ctx: CTX): RUNNER = {
				waitingForLoad match {
					case NoLoadWait =>
						ctx.signal(manager, LoadArrival(fromEp.channelName, ld))
						Processor.DomainRun.same
					case WaitInducting(ep, toLoc) =>
						val loc =
							(inboundRouting.inductByName(fromEp.channelName) orElse outboundRouting.inductByName(fromEp.channelName)).map(t => At(t._1))
						if(loc isEmpty) throw new RuntimeException(s"Undefined induct ${fromEp.channelName} for XSwitch($name)")
						loc.foreach(carriageComponent.inductFrom(fromEp, _))
						busyGuard orElse channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducting(ep, toLoc))
				}
			}
			override def loadReleased(endpoint: Channel.End[MaterialLoad, XSwitchSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: CTX): RUNNER = Processor.DomainRun.same
			val end = chOps.registerEnd(this)
		}.end

	private sealed trait WaitForChannel
	private case object NoChannelWait extends WaitForChannel
	private case class WaitDischarging(ch: Channel.Start[MaterialLoad, XSwitchSignal], loc: SlotLocator) extends WaitForChannel
	private var waitingForChannel: WaitForChannel = NoChannelWait
	private def outboundSource(chOps: Channel.Ops[MaterialLoad, XSwitchSignal, _])(implicit ctx: CTX) =
		new Channel.Source[MaterialLoad, XSwitchSignal] {
			override val ref: Processor.Ref = ctx.aCtx.self
			val start = chOps.registerStart(this)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, XSwitchSignal], load: MaterialLoad)(implicit ctx: CTX): RUNNER =
				waitingForChannel match {
					case NoChannelWait => Processor.DomainRun.same
					case WaitDischarging(ch, loc) =>
						carriageComponent.dischargeTo(ch, loc)
						busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
				}
		}.start


	private class RoutingGroup(inducts: Map[Int, Channel.End[MaterialLoad, XSwitchSignal]], discharges: Map[Int, Channel.Start[MaterialLoad, XSwitchSignal]]) {
		private val inductsByName = inducts.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		private val dischargesByName = discharges.map{case (idx, ch) => ch.channelName -> (idx, ch)}
		lazy val loadListener: RUNNER = if(inducts isEmpty) Processor.DomainRun.noOp else inducts.values.map(_.loadReceiver).reduce((l,r) => l orElse r)
		lazy val ackListener: RUNNER = if(discharges isEmpty) Processor.DomainRun.noOp else discharges.values.map(_.ackReceiver).reduce((l,r) => l orElse r)
		def inductByLoc(loc: SlotLocator) = inducts.get(loc.idx)
		def dischargeByLoc(loc: SlotLocator) = discharges.get(loc.idx)
		def inductByName(chName: String) = inductsByName.get(chName)
		def dischargeByName(chName: String) = dischargesByName.get(chName)
		def route(from: SlotLocator, to: SlotLocator): Option[(Channel.End[MaterialLoad, XSwitchSignal], Channel.Start[MaterialLoad, XSwitchSignal])] =
			for{
				induct <- inductByLoc(from)
				discharge <- dischargeByLoc(to)
			} yield induct -> discharge
		def route(from: String, to: String): Option[((Int, Channel.End[MaterialLoad, XSwitchSignal]), (Int, Channel.Start[MaterialLoad, XSwitchSignal]))] =
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
						manager = ctx.from
						inboundRouting = new RoutingGroup(configuration.inboundInduction.map{case (idx, ch) => idx -> inboundSink(ch)}, configuration.inboundDischarge.map{case (idx, ch) => idx -> outboundSource(ch)})
						outboundRouting = new RoutingGroup(configuration.outboundInduction.map{case (idx, ch) => idx -> inboundSink(ch)}, configuration.outboundDischarge.map{case (idx, ch) => idx -> outboundSource(ch)})
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

	private def executeCommand(cmd: ExternalCommand)(body: => RUNNER)(implicit ctx: CTX): RUNNER =
		if(currentCommand isEmpty) {
			currentCommand = Some(cmd)
			body
		} else rejectExternalCommand(cmd, s"XSwitch($name) is busy")

	private def rejectExternalCommand(cmd: ExternalCommand, msg: String)(implicit ctx: CTX): RUNNER = {
		ctx.reply(NotAcceptedCommand(cmd, msg))
		Processor.DomainRun.same
	}
	private lazy val IDLE: RUNNER = channelListener orElse {
		implicit ctx: CTX => {
			case cmd @ Transfer(from, to) => executeCommand(cmd) {
				val route: Option[((At, Channel.End[MaterialLoad, XSwitchSignal]), (At, Channel.Start[MaterialLoad, XSwitchSignal]))] =
					(inboundRouting.route(from, to) orElse outboundRouting.route(from, to))
					.map{case ((inductIdx, i), (dischargeIdx, d)) =>
						((At(inductIdx), i), (At(dischargeIdx), d))}
				route match {
					case Some(((inductLoc, induct), (dischargeLoc, discharge))) =>
						carriageComponent.inductFrom(induct, inductLoc)
						busyGuard orElse channelListener orElse carriageComponent.INDUCTING(dischargeAfterInducting(discharge, dischargeLoc))
					case None =>
						currentCommand = None
						rejectExternalCommand(cmd, s"Invalid Requested Routing: $from -> $to for XSwitch($name)")
				}
			}
		}
	}

	private def dischargeAfterInducting(ch: Channel.Start[MaterialLoad, XSwitchSignal], loc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				waitingForLoad = NoLoadWait
				carriageComponent.dischargeTo(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitingForLoad = WaitInducting(ch, loc)
				Processor.DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => throw new RuntimeException(s"Carriage Failed Full while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}


	private def afterTryDischarge(ch: Channel.Start[MaterialLoad, XSwitchSignal], loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				waitingForChannel = NoChannelWait
				currentCommand.foreach(cmd => ctx.signal(manager, CompletedCommand(cmd)))
				currentCommand = None
				IDLE
			case CarriageComponent.OperationOutcome.InTransit => Processor.DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitingForChannel = WaitDischarging(ch, loc)
				busyGuard orElse channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty while executing: $currentCommand at ${ctx.now} by XSwitch($name)")
		}
	}

}
