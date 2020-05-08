/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate.{DomainConfigure, DomainMessageProcessor, DomainRun}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.ddes.{AgentTemplate, Clock, Simulation, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.protocols.Equipment.XSwitchSignal
import com.saldubatech.protocols.{Equipment, EquipmentManagement}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.abstractions.InductDischargeUnit.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.units.abstractions.{InductDischargeUnit, LoadAwareUnit}
import com.saldubatech.units.carriage.{At, CarriageComponent, CarriageTravel, SlotLocator}
import com.saldubatech.util.LogEnabled


object LoadAwareXSwitch {

	sealed abstract class ConfigurationCommand extends Identification.Impl() with Equipment.XSwitchSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with Equipment.XSwitchSignal
	case class Transfer(load: MaterialLoad, toCh: String) extends ExternalCommand

	
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class FailedBusy(cmd: ExternalCommand, msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class FailedWaiting(msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, msg: String) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class CompletedConfiguration(self: SimRef[XSwitchSignal]) extends Identification.Impl() with EquipmentManagement.XSwitchNotification
	case class MaxCommandsReached(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.XSwitchNotification

	sealed trait InternalSignal extends Equipment.XSwitchSignal
	case class Execute(cmd: ExternalCommand) extends Identification.Impl() with InternalSignal

	trait AfferentChannel extends Channel.Afferent[MaterialLoad, Equipment.XSwitchSignal] { self =>
		override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.XSwitchSignal
		override type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.XSwitchSignal
		override type DeliverSignal = Channel.DeliverLoad[MaterialLoad] with Equipment.XSwitchSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Distance) = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.XSwitchSignal
		override def deliverBuilder(channel: String) = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.XSwitchSignal
	}

	trait EfferentChannel extends Channel.Efferent[MaterialLoad, Equipment.XSwitchSignal] {
		override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.XSwitchSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
	}

	case class Configuration[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
		OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
	(physics: CarriageTravel,
	 maxPendingCommands: Int,
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
		new  AgentTemplate.Wrapper[Equipment.XSwitchSignal](name, clockRef, simController, new LoadAwareXSwitch(name, configuration).configurer)
	}
}

class LoadAwareXSwitch[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
	OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
(override val name: String, configuration: LoadAwareXSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	extends Identification.Impl(name) with LoadAwareUnit[Equipment.XSwitchSignal] with InductDischargeUnit[Equipment.XSwitchSignal] with LogEnabled {
	import LoadAwareXSwitch._

	sealed trait CarriageSignal extends Equipment.XSwitchSignal
	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with CarriageSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with CarriageSignal
	case class Induct(override val from: INDUCT, override val at: SlotLocator)
		extends InductCmd[Equipment.XSwitchSignal](from, at) with CarriageSignal
	case class Discharge(override val to: DISCHARGE, override val at: SlotLocator)
		extends DischargeCmd[Equipment.XSwitchSignal](to, at) with CarriageSignal

	override type HOST = LoadAwareXSwitch[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal]
	override type EXTERNAL_COMMAND = ExternalCommand
	override type PRIORITY_COMMAND = Nothing
	override type INBOUND_LOAD_COMMAND = Transfer
	override type NOTIFICATION = EquipmentManagement.XSwitchNotification
	override type LOAD_SIGNAL = Load
	override type UNLOAD_SIGNAL = Unload
	override type INDUCT_SIGNAL = Induct
	override type DISCHARGE_SIGNAL = Discharge

	override def execSignal(cmd: ExternalCommand) = Execute(cmd)
	override def loader(loc: SlotLocator) = Load(loc)
	override def unloader(loc: SlotLocator) = Unload(loc)
	override def inducter(from: INDUCT, at: SlotLocator) = Induct(from, at)
	override def discharger(to: DISCHARGE, at: SlotLocator) = Discharge(to, at)



	override protected val maxPendingCommands = configuration.maxPendingCommands
	override protected def maxCommandsReached(cmd: ExternalCommand) = MaxCommandsReached(cmd)
	override protected def loadArrival(chName: String, ld: MaterialLoad) = LoadArrival(chName, ld)
	protected def notAcceptedNotification(cmd: ExternalCommand, msg: String) = NotAcceptedCommand(cmd, msg)
	protected def completedCommandNotification(cmd: ExternalCommand) = CompletedCommand(cmd)

	val loadArrivalBehavior: (INDUCT, MaterialLoad, Option[Distance], CTX) => Function1[InductDischargeUnit.WaitForLoad, RUNNER] =
		(induct: INDUCT, load: MaterialLoad, idx: Option[Distance], ctx: CTX) => { wflState =>
			implicit val iCtx = ctx
			gotLoad(ctx.now -> load, induct.channelName)
			wflState match {
				case NoLoadWait =>
					triggerNext(DomainRun.same)
				case WaitInductingToDischarge(to, toLoc, from) if (from.channelName == induct.channelName) =>
					val loc =
						(inboundRouting.inductByName(induct.channelName) orElse outboundRouting.inductByName(induct.channelName)).map(t => At(t._1))
					loc.map(carriageComponent.inductFrom(induct, _)(ctx)) orElse {throw new RuntimeException(s"Undefined induct ${induct.channelName} for XSwitch($name)")}
					endLoadWait
					triggerNext(continueCommand(channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(from, to, toLoc))))
			}
		}

	private val channelFreeBehavior: (DISCHARGE, MaterialLoad, CTX) => PartialFunction[InductDischargeUnit.WaitForChannel, RUNNER] = {
		(toCh: DISCHARGE, ld: MaterialLoad, ctx: CTX) => {
			case NoChannelWait =>
				implicit val iCtx = ctx
				triggerNext(DomainRun.same)
			case WaitDischarging(ch, loc) =>
				implicit val iCtx = ctx
				carriageComponent.dischargeTo(ch, loc)(ctx)
				triggerNext(continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc))))
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
					case LoadAwareXSwitch.NoConfigure =>
						installManager(ctx.from)
						installSelf(ctx.aCtx.self)
						inboundRouting = new RoutingGroup(
							configuration.inboundInduction.map{
								case (idx, ch) => idx -> InductDischargeUnit.inductSink[Equipment.XSwitchSignal, HOST](LoadAwareXSwitch.this)(loadArrivalBehavior)(At(idx), ch)},
							configuration.inboundDischarge.map{case (idx, ch) => idx -> InductDischargeUnit.dischargeSource[Equipment.XSwitchSignal, HOST](LoadAwareXSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
						outboundRouting = new RoutingGroup(
							configuration.outboundInduction.map{
								case (idx, ch) => idx -> InductDischargeUnit.inductSink[Equipment.XSwitchSignal, HOST](LoadAwareXSwitch.this)(loadArrivalBehavior)(At(idx),ch)},
							configuration.outboundDischarge.map{case (idx, ch) => idx -> InductDischargeUnit.dischargeSource[Equipment.XSwitchSignal, HOST](LoadAwareXSwitch.this)(At(idx), manager, ch)(channelFreeBehavior)})
						ctx.configureContext.signal(manager, CompletedConfiguration(ctx.aCtx.self))
						idleExecutor
				}
			}
		}
	}
	private lazy val loadListener: RUNNER = inboundRouting.loadListener orElse outboundRouting.loadListener
	private lazy val ackListener: RUNNER = inboundRouting.ackListener orElse outboundRouting.ackListener
	private lazy val channelListener: RUNNER = loadListener orElse ackListener

	private lazy val idleExecutor = continueCommand {
		channelListener orElse {
			implicit ctx: CTX => {
				case Execute(cmd) => processCmd(ctx)(cmd)
			}
		}
	}

	def processCmd(implicit ctx: CTX): PartialFunction[ExternalCommand, RUNNER] = {
			case cmd@Transfer(load, to) =>
				val routingResult = for {
					fromInduct <- inboundRouting.inducts.find { case (idx, induct) => induct.peekNext.exists(_._1.uid == load.uid)} orElse outboundRouting.inducts.find { case (idx, induct) => induct.peekNext.exists(_._1.uid == load.uid)}
					from = fromInduct._2.channelName
					route <- inboundRouting.route(from, to) orElse outboundRouting.route(from, to)
				} yield {
					((At(route._1._1) -> route._1._2, At(route._2._1) -> route._2._2)) match {
						case ((inductLoc, induct), (dischargeLoc, discharge)) =>
							carriageComponent.inductFrom(induct, inductLoc)
							continueCommand(channelListener orElse carriageComponent.INDUCTING(completeInductingAndDischarge(induct, discharge, dischargeLoc)))
					}
				}
			if(routingResult nonEmpty) routingResult.head
			else completeCommand(idleExecutor, notAcceptedNotification(_, s"Invalid Requested Routing for $cmd for XSwitch($name)"))
	}


	private def completeInductingAndDischarge(from: INDUCT, dischargeChannel: DISCHARGE, dischargeLoc: SlotLocator): CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.LoadOperationOutcome.Loaded =>
				endLoadWait
				carriageComponent.dischargeTo(dischargeChannel, dischargeLoc)
				continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(dischargeChannel, dischargeLoc)))
			case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
				waitInductingToDischarge(dischargeChannel, dischargeLoc, from)
				DomainRun.same
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.LoadOperationOutcome.ErrorTrayFull => throw new RuntimeException(s"Carriage Failed Full while executing at ${ctx.now} by XSwitch($name)")
		}
	}


	private def afterTryDischarge(ch: DISCHARGE, loc: SlotLocator): CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, RUNNER] = {
		implicit ctx => {
			case CarriageComponent.UnloadOperationOutcome.Unloaded =>
				endChannelWait
				completeCommand(idleExecutor, completedCommandNotification)
			case CarriageComponent.OperationOutcome.InTransit => DomainRun.same
			case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
				waitDischarging(ch, loc)
				continueCommand(channelListener orElse carriageComponent.DISCHARGING(afterTryDischarge(ch, loc)))
			case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty => throw new RuntimeException(s"Carriage Failed Empty at ${ctx.now} by XSwitch($name)")
		}
	}


}
