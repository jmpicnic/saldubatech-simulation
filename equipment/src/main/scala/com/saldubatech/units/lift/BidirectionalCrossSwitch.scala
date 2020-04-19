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
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.units.lift
import com.saldubatech.util.LogEnabled


object BidirectionalCrossSwitch {
	trait CrossSwitchSignal extends Identification

	type CTX = Processor.SignallingContext[CrossSwitchSignal]
	type RUNNER = Processor.DomainRun[CrossSwitchSignal]

	sealed abstract class ConfigurationCommand extends Identification.Impl() with CrossSwitchSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with CrossSwitchSignal
	case class Transfer(fromCh: String, toCh: String) extends ExternalCommand

	sealed abstract class Notification extends Identification.Impl() with EquipmentManager.Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class FailedBusy(cmd: ExternalCommand, msg: String) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	sealed abstract class InternalSignal extends Identification.Impl() with CrossSwitchSignal
	case class SlotBecomesAvailable(slot: Carriage.Slot) extends InternalSignal


	case class Configuration[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
		OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 physics: Carriage.CarriageTravel,
	 inboundInduction: Seq[(Int, Channel.Ops[MaterialLoad, InboundInductSignal, CrossSwitchSignal])],
	 inboundDischarge: Seq[(Int, Channel.Ops[MaterialLoad, CrossSwitchSignal, InboundDischargeSignal])],
	 outboundInduction: Seq[(Int, Channel.Ops[MaterialLoad, OutboundInductSignal, CrossSwitchSignal])],
	 outboundDischarge: Seq[(Int, Channel.Ops[MaterialLoad, CrossSwitchSignal, OutboundDischargeSignal])],
	 initialAlignment: Int
	)

	def buildProcessor[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
		OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
	(configuration: Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
	(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val carriage = Carriage.buildProcessor(s"${configuration.name}_carriage", configuration.physics, clockRef,simController)
		new Processor[BidirectionalCrossSwitch.CrossSwitchSignal](configuration.name, clockRef, simController, new BidirectionalCrossSwitch(configuration, carriage).configurer)
	}

	/*
	FUNCTIONAL UTILITIES
	 */
	private val ignoreSlotAvailable: RUNNER = Processor.DomainRun {
		case SlotBecomesAvailable(_) =>	Processor.DomainRun.same
	}
	private val rejectExternalCommand: RUNNER = (ctx: CTX) => {
		case cmd: ExternalCommand =>
			ctx.reply(FailedBusy(cmd, "Command cannot be processed. Processor is Busy"))
			Processor.DomainRun.same
	}


}

class BidirectionalCrossSwitch[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
	OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
(configuration: BidirectionalCrossSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal], carriage: Processor[Carriage.CarriageSignal]) extends LogEnabled {
	import BidirectionalCrossSwitch._
	private var manager: Processor.Ref = _


	private def inboundSink(assignedSlot: Carriage.Slot,
	                        manager: Processor.Ref,
	                        chOps: Channel.Ops[MaterialLoad, _, CrossSwitchSignal], host: Processor.Ref): Channel.End[MaterialLoad, CrossSwitchSignal] =
		new Channel.Sink[MaterialLoad, CrossSwitchSignal] {
			override val ref: Processor.Ref = host

			override def loadArrived(endpoint: Channel.End[MaterialLoad, CrossSwitchSignal], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: CTX): RUNNER = {
				refreshInducts
				ctx.signal(manager, BidirectionalCrossSwitch.LoadArrival(endpoint.channelName, load))
				if (assignedSlot.isEmpty) {
					ctx.aCtx.log.info(s"XCSink: Pulling load $load into slot $assignedSlot")
					endpoint.get(load).foreach(t => assignedSlot.store(t._1))
				} else {
					ctx.aCtx.log.info(s"XCSink: Slot $assignedSlot is full with ${assignedSlot.inspect}, leaving load $load in Channel ${endpoint.channelName}")
				}
				ctx.aCtx.log.info(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[CrossSwitchSignal]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, CrossSwitchSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: CTX): RUNNER = {
				//refreshInducts
				//if (assignedSlot.isEmpty) endpoint.getNext.foreach(t => assignedSlot.store(t._1))
				ctx.aCtx.log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same
			}
			val end = chOps.registerEnd(this)
		}.end


	private def outboundSource(slot: Carriage.Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, CrossSwitchSignal, _], host: Processor.Ref) =
		new Channel.Source[MaterialLoad, CrossSwitchSignal] {
			override val ref: Processor.Ref = host
			val start = chOps.registerStart(this)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, CrossSwitchSignal], load: MaterialLoad)(implicit ctx: CTX): RUNNER = {
				if (slot.inspect nonEmpty) ctx.signalSelf(SlotBecomesAvailable(slot))
				Processor.DomainRun.same
			}
		}.start

	private case class Discharge(slot: Carriage.Slot, destination: Channel.Start[MaterialLoad, CrossSwitchSignal])

	val nopRunner: Processor.DomainRun[BidirectionalCrossSwitch.CrossSwitchSignal] = (ctx: BidirectionalCrossSwitch.CTX) => {
		case n: Any if false => Processor.DomainRun.same
	}

	private class DischargeConfig[DESTINATION_SIGNAL >: ChannelConnections.ChannelDestinationMessage](discharges: Seq[(Int, Channel.Ops[MaterialLoad, CrossSwitchSignal, _])],
	                                                                                                  manager: Processor.Ref, locatorFactory: Int => Carriage.SlotLocator)(implicit ctx: BidirectionalCrossSwitch.CTX) {
		val slots = discharges.map{ case (idx, ops) => ops.ch.name -> Carriage.Slot(locatorFactory(idx))}.toMap
		val destinations = discharges.map(_._2).map(ops => ops.ch.name -> outboundSource(slots(ops.ch.name),manager,ops,ctx.aCtx.self)).toMap
		val listener =  if(discharges isEmpty) nopRunner else discharges.map(_._2).map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)

		def slot(chName: String): Option[Carriage.Slot] = slots.get(chName)
		def destination(chName: String): Option[Channel.Start[MaterialLoad, CrossSwitchSignal]] = destinations.get(chName)
		def endpoint(chName: String): Option[Discharge] = (slot(chName), destination(chName)) match {
			case (Some(sl), Some(dst)) => Some(Discharge(sl, dst))
			case (None, None) => None
			case other => throw new IllegalStateException(s"Inconsistent Discharge Configuration for $chName: $other")
		}

	}

	private case class InductHolder(slot: Carriage.Slot, source: Channel.End[MaterialLoad, CrossSwitchSignal])
	private class InductConfiguration[SOURCE_SIGNAL >: ChannelConnections.ChannelSourceMessage](inducts: Seq[(Int, Channel.Ops[MaterialLoad, SOURCE_SIGNAL, CrossSwitchSignal])],
	                                                                                            manager: Processor.Ref, locatorFactory: Int => Carriage.SlotLocator)(implicit ctx: BidirectionalCrossSwitch.CTX) {
		val slots = inducts.map{case (idx, ops) => ops.ch.name -> Carriage.Slot(locatorFactory(idx))}.toMap
		val sources = inducts.map(_._2).map(ops => ops.ch.name -> ops).map(c => c._1 -> inboundSink(slots(c._1), manager, c._2, ctx.aCtx.self)).toMap
		val listener = if(inducts isEmpty) nopRunner else inducts.map(_._2).map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

		def slot(chName: String): Option[Carriage.Slot] = slots.get(chName)
		def source(chName: String): Option[Channel.End[MaterialLoad, CrossSwitchSignal]] = sources.get(chName)

		def endpoint(chName: String): Option[InductHolder] = (slot(chName), source(chName)) match {
			case (Some(sl), Some(src)) => Some(InductHolder(sl, src))
			case (None, None) => None
			case other => throw new IllegalStateException(s"Inconsistent Induct Configuration for $chName: $other")
		}
	}

	private var outboundInductConfiguration: InductConfiguration[OutboundInductSignal] = _
	private var outboundDischargeConfiguration: DischargeConfig[OutboundDischargeSignal] = _
	private var inboundInductConfiguration: InductConfiguration[InboundInductSignal] = _
	private var inboundDischargeConfiguration: DischargeConfig[InboundDischargeSignal] = _

	private var endpointListener: BidirectionalCrossSwitch.RUNNER = _

	private var carriageRef: Processor.Ref = _

	private def configurer: Processor.DomainConfigure[CrossSwitchSignal] = {
		new Processor.DomainConfigure[CrossSwitchSignal] {
			override def configure(config: CrossSwitchSignal)(implicit ctx: BidirectionalCrossSwitch.CTX): Processor.DomainMessageProcessor[CrossSwitchSignal] = {
				config match {
					case BidirectionalCrossSwitch.NoConfigure =>
						manager = ctx.from
						carriageRef = ctx.aCtx.spawn(carriage.init, carriage.processorName)
						outboundInductConfiguration = new InductConfiguration(configuration.outboundInduction, manager, Carriage.OnLeft)
						outboundDischargeConfiguration = new DischargeConfig(configuration.outboundDischarge, manager, Carriage.OnLeft)

						inboundInductConfiguration = new InductConfiguration(configuration.inboundInduction, manager, Carriage.OnRight)
						inboundDischargeConfiguration = new DischargeConfig(configuration.inboundDischarge, manager, Carriage.OnRight)

						endpointListener =
							Seq(outboundInductConfiguration.listener, outboundDischargeConfiguration.listener, inboundInductConfiguration.listener, inboundDischargeConfiguration.listener).reduce((l, r) => l orElse r)

						ctx.configureContext.signal(carriageRef, Carriage.Configure(configuration.initialAlignment))
						//ctx.reply(BidirectionalCrossSwitch.CompletedConfiguration(ctx.aCtx.self))
						WAITING_FOR_CARRIAGE_CONFIGURATION
				}
			}
		}
	}

	def WAITING_FOR_CARRIAGE_CONFIGURATION(implicit ctx: lift.BidirectionalCrossSwitch.CTX) = {
		log.debug(s"Setting up waitingForCarriage Configuration for Lift Level: ${configuration.name}")
		new Processor.DomainConfigure[CrossSwitchSignal] {
			override def configure(config: CrossSwitchSignal)(implicit ctx: lift.BidirectionalCrossSwitch.CTX): Processor.DomainMessageProcessor[CrossSwitchSignal] = {
				config match {
					case Carriage.CompleteConfiguration(pr) if pr == carriageRef =>
						// This is be needed in the future to signal the manager
						log.debug(s"Completing CarriageLevel Configuration")
						ctx.configureContext.signal(manager, BidirectionalCrossSwitch.CompletedConfiguration(ctx.aCtx.self))
						IDLE
				}
			}
		}
	}

	private lazy val IDLE: BidirectionalCrossSwitch.RUNNER = endpointListener orElse ignoreSlotAvailable orElse {
		implicit ctx: BidirectionalCrossSwitch.CTX => {
			case cmd @ Transfer(from, to) =>
				refreshInducts
				implicit val c = cmd
				(doTransfer _).tupled(resolveEndpoints(from, to))
		}
	}


	private def resolveEndpoints(from: String, to: String): (InductHolder, Discharge) = {
		// The following should be replaced by a call back to validate endpoint compatibility according to the specific equipment (e.g. does it allow loopback?)
		val maybeInboundFrom = inboundInductConfiguration.endpoint(from)
		val (inboundFrom, fromEp) =
			if(maybeInboundFrom nonEmpty) (true, maybeInboundFrom.head)
			else (false, outboundInductConfiguration.endpoint(from).head)
		val toEp =
			if(inboundFrom) inboundDischargeConfiguration.endpoint(to).getOrElse(throw new IllegalArgumentException(s"At ${configuration.name}: Getting Endpoint for $to from ${inboundDischargeConfiguration.destinations}"))
			else outboundDischargeConfiguration.endpoint(to).head
		(fromEp, toEp)
	}
	private def doTransfer(fromEp: InductHolder, toEp: Discharge) (implicit cmd: ExternalCommand, ctx: BidirectionalCrossSwitch.CTX): BidirectionalCrossSwitch.RUNNER = {
		implicit val requester = ctx.from
		ctx.signal(carriageRef, Carriage.GoTo(fromEp.slot))
		FETCHING(fetchSuccessCtx => {
			fetchSuccessCtx.signal(carriageRef, Carriage.GoTo(toEp.slot))
			DELIVERING(
				deliverSuccessCtx => doDischarge(toEp)(deliverSuccessCtx),
				_ => WAITING_FOR_SLOT(toEp))
		},
			_ => WAITING_FOR_LOAD(fromEp)(
				loadReceivedCtx => {
					loadReceivedCtx.signal(carriageRef, Carriage.GoTo(toEp.slot))
					DELIVERING(
						deliveringSuccessCtx => doDischarge(toEp)(deliveringSuccessCtx),
						_ => WAITING_FOR_SLOT(toEp)
					)
				}
			)
		)
	}

	private def doDischarge(ep: Discharge)(ctx: BidirectionalCrossSwitch.CTX)(implicit cmd: ExternalCommand, requester: Processor.Ref): BidirectionalCrossSwitch.RUNNER =
		if(ep.slot.inspect.isEmpty) throw new IllegalStateException(s"Unexpected Empty Discharge slot while executing $cmd for $requester")
		else if (ep.slot.inspect.exists(ep.destination.send(_)(ctx))) {
			ep.slot.retrieve
			ctx.signal(manager, CompletedCommand(cmd))
			IDLE
		} else WAITING_FOR_SLOT(ep)

	private def refreshInducts(implicit ctx: BidirectionalCrossSwitch.CTX): Unit = {
		for{
			cfg <- Seq(inboundInductConfiguration, outboundInductConfiguration)
			(ch, ep) <- cfg.sources
			sl <- cfg.slots.get(ch)
		} {
			println(s"#### $ch::$sl --> ${sl.inspect}::${sl.isEmpty}")
			val ld = ep.getNext
			if (ld nonEmpty) {
				if (sl.isEmpty) {
					sl.store(ld.head._1)
					println(s"#### $ch::$sl --> ${sl.inspect} Now stored")
					ctx.aCtx.log.info(s"#### $ch::$sl done store by ${ctx.aCtx.self} at ${ctx.now} in here: ${new RuntimeException("").getStackTrace.mkString("\n")}")
				} else {
					println(s"#### $ch::$sl --> ${sl.inspect}::${sl.isEmpty} Inner Check Full")
					ctx.aCtx.log.info(s"#### $ch::$sl changed all of the sudden, now it is ${sl.inspect} in ${ctx.aCtx.self} at ${ctx.now}")
					ctx.aCtx.log.info(s"#### $ch::$sl changed all of the sudden, now it is ${sl.inspect} in ${ctx.aCtx.self} at ${ctx.now} in here ${new RuntimeException("").getStackTrace.mkString("\n")}")
				}
			}
		}
	}

	private def FETCHING(success: DelayedDomainRun[CrossSwitchSignal],
	                     fail: DelayedDomainRun[CrossSwitchSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): BidirectionalCrossSwitch.RUNNER =
		endpointListener orElse ignoreSlotAvailable orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) =>
					refreshInducts
					if(!destination.isEmpty) {
						ctx.signal(carriageRef, Carriage.Load(destination))
						LOADING(success)
					}
					else {
						println(s"##### Destination ${destination} is Empty, Cannot Load")
						fail(ctx)
					}
				case _: CarriageNotification =>
					fail(ctx)
			}
	} orElse rejectExternalCommand

	private def DELIVERING(success: DelayedDomainRun[CrossSwitchSignal],
	                       fail: DelayedDomainRun[CrossSwitchSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): BidirectionalCrossSwitch.RUNNER =
		rejectExternalCommand orElse endpointListener orElse ignoreSlotAvailable orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) =>
					refreshInducts
					if(destination isEmpty) {
						ctx.signal(carriageRef, Carriage.Unload(destination))
						UNLOADING(success)
					} else fail(ctx)
				case _: CarriageNotification =>
					fail(ctx)
			}
	}

	private def LOADING(continue: DelayedDomainRun[CrossSwitchSignal]): BidirectionalCrossSwitch.RUNNER =
		rejectExternalCommand orElse endpointListener orElse ignoreSlotAvailable orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case Carriage.Loaded(Carriage.Load(_), _) =>
					refreshInducts
					continue(ctx)
			}
		}

	private def WAITING_FOR_LOAD(from: InductHolder)(
		continue: DelayedDomainRun[CrossSwitchSignal]): BidirectionalCrossSwitch.RUNNER =
		rejectExternalCommand orElse outboundDischargeConfiguration.listener orElse inboundDischargeConfiguration.listener orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.source.channelName =>
					// First do the endpoint housekeeping
					from.source.doEndpointReceiving(tr.load, tr.resource)(ctx)
					if (from.slot isEmpty) { // If the receiving slot is initially empty
						// try to receive from the channel, which **MAY** refresh the slots as a side effect (!!)
						val load = from.source.getNext
						if(from.slot.isEmpty && load.contains(tr.load)) {

						}
						if(!from.slot.isEmpty) { // succesfully loaded
							ctx.signal(carriageRef, Carriage.Load(from.slot)) // Signal the carriage
							LOADING(continue) // wait for loading complete
						} else throw new IllegalStateException(s"Location ${from.slot} is empty after refresh from channel ${from.source.channelName}, it should contain ${tr.load}")
					} else {
						throw new IllegalStateException(s"Location ${from.slot} is not empty to receive load ${tr.load} from channel ${from.source.channelName}")
					}
				case other =>
					refreshInducts
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.source.channelName}")
			}
		} orElse outboundInductConfiguration.listener orElse inboundInductConfiguration.listener orElse ignoreSlotAvailable

	private def WAITING_FOR_SLOT(to: Discharge)(implicit cmd: ExternalCommand, requester: Processor.Ref): BidirectionalCrossSwitch.RUNNER =
		rejectExternalCommand orElse endpointListener orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case SlotBecomesAvailable(slot) if slot == to.slot =>
					refreshInducts
					if(slot.isEmpty) {
						log.info(s"DISCHARGE SLOT: EMPTY")
						IDLE
					}
					else {
						log.info(s"DISCHARGE SLOT: FULL ${to.slot.inspect}")
						doDischarge(to)(ctx)
					}
			}
		}

	private def UNLOADING(continue: DelayedDomainRun[CrossSwitchSignal]): BidirectionalCrossSwitch.RUNNER =
		rejectExternalCommand orElse endpointListener orElse ignoreSlotAvailable orElse {
			implicit ctx: BidirectionalCrossSwitch.CTX => {
				case Carriage.Unloaded(Carriage.Unload(_), _) =>
					refreshInducts
					continue(ctx)
			}
		}

}
