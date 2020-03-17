package com.saldubatech.units.lift

import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.{CommandContext, DelayedDomainRun}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object FanIn {
	trait LiftAssemblySignal extends Identification

	type CTX = Processor.SignallingContext[LiftAssemblySignal]
	type RUNNER = Processor.DomainRun[LiftAssemblySignal]

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with LiftAssemblySignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class FanInCommand extends Identification.Impl() with LiftAssemblySignal
	case class Transfer(fromCh: String) extends FanInCommand
	case object OutputFromTray extends FanInCommand

	sealed abstract class Notification extends Identification.Impl() with LiftAssemblySignal
	case class CompletedCommand(cmd: FanInCommand) extends Notification
	case class FailedFull(cmd: FanInCommand, msg: String) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	sealed abstract class InternalSignal extends Identification.Impl() with LiftAssemblySignal
	case class SlotBecomesAvailable(slot: Carriage.Slot) extends InternalSignal

	case class Configuration[CollectorMessage >: ChannelConnections.ChannelSourceMessage, DischargeMessage >: ChannelConnections.ChannelDestinationMessage]
	(carriage: Carriage.Ref,
	 collectors: Seq[(Carriage.Slot, Channel[MaterialLoad, CollectorMessage, LiftAssemblySignal])],
	 discharge: (Carriage.Slot, Channel[MaterialLoad, LiftAssemblySignal, DischargeMessage])
	) {
		val collectorOps = collectors.map(t => new Channel.Ops(t._2))
		val dischargeOps = new Channel.Ops(discharge._2)
	}


	private def defaultSink(assignedSlot: Carriage.Slot,
	                        manager: Processor.Ref,
	                        chOps: Channel.Ops[MaterialLoad, _, LiftAssemblySignal], host: Processor.Ref) =
		(new Channel.Sink[MaterialLoad, LiftAssemblySignal] {
			override val ref: Processor.Ref = host

			override def loadArrived(endpoint: Channel.End[MaterialLoad, LiftAssemblySignal], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: CTX): RUNNER = {
				ctx.signal(manager, FanIn.LoadArrival(endpoint.channelName, load))
				if (assignedSlot.isEmpty) {
					ctx.aCtx.log.debug(s"DefaultSink: Pulling load $load into slot $assignedSlot")
					endpoint.get(load).foreach(t => assignedSlot.store(t._1))
				} else {
					ctx.aCtx.log.debug(s"DefaultSink: Slot $assignedSlot is full, leaving load $load in Channel ${endpoint.channelName}")
				}
				ctx.aCtx.log.debug(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[LiftAssemblySignal]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, LiftAssemblySignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: CTX): RUNNER = {
				if (assignedSlot.isEmpty) endpoint.getNext.foreach(t => assignedSlot.store(t._1))
				ctx.aCtx.log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[LiftAssemblySignal]
			}

			val end = chOps.registerEnd(this)
		}).end


	private def defaultSource(slot: Carriage.Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, LiftAssemblySignal, _], host: Processor.Ref) =
		(new Channel.Source[MaterialLoad, LiftAssemblySignal] {
			override val ref: Processor.Ref = host

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, LiftAssemblySignal], load: MaterialLoad)(implicit ctx: CTX): RUNNER = {
				if (slot.inspect.exists(start.send(_))) {
					slot.retrieve
					ctx.signalSelf(SlotBecomesAvailable(slot))
				}
				else {
					ctx.signal(manager, FanIn.LoadAcknowledged(endpoint.channelName, load))
				}
				ctx.aCtx.log.debug(s"Finishing Load Acknowledge at Source for ${endpoint.channelName}")
				Processor.DomainRun.same[LiftAssemblySignal]
			}

			val start = chOps.registerStart(this)
		}).start


	def buildProcessor[CollectorSignal >: ChannelConnections.ChannelSourceMessage, DischargeSignal >: ChannelConnections.ChannelDestinationMessage]
	(
		name: String,
		configuration: Configuration[CollectorSignal, DischargeSignal])
	(implicit clockRef: Clock.ClockRef, simController: SimulationController.ControllerRef) = {
		val domain = new FanIn(name, configuration)
		new Processor[FanIn.LiftAssemblySignal](name, clockRef, simController, domain.configurer)
	}

	private val ignoreSlotAvailable: Processor.DomainRun[LiftAssemblySignal] = Processor.DomainRun {
		case SlotBecomesAvailable(_) => Processor.DomainRun.same[LiftAssemblySignal]
	}

}

class FanIn[CollectorMessage >: ChannelConnections.ChannelSourceMessage, DischargeMessage >: ChannelConnections.ChannelDestinationMessage]
(name: String, configuration: FanIn.Configuration[CollectorMessage, DischargeMessage]) extends LogEnabled {
	import FanIn._
	private var manager: Processor.Ref = null

	private val collectorSlots: mutable.Map[String, Carriage.Slot] = mutable.Map.empty
	private val collectorChannels: mutable.Map[String, Channel.End[MaterialLoad, LiftAssemblySignal]] = mutable.Map.empty
	private var collectorChannelListeners: FanIn.RUNNER = null

	private var dischargeSlot: Carriage.Slot = null
	private var dischargeChannel: Channel.Start[MaterialLoad, LiftAssemblySignal] = null
	private var dischargeChannelListener: FanIn.RUNNER = null



	private def configurer: Processor.DomainConfigure[LiftAssemblySignal] = {
		log.debug(s"Setting up initial Configuration for Lift Level: $name")
		new Processor.DomainConfigure[LiftAssemblySignal] {
			override def configure(config: LiftAssemblySignal)(implicit ctx: FanIn.CTX): Processor.DomainMessageProcessor[LiftAssemblySignal] = {
				config match {
					case cmd@FanIn.NoConfigure =>
						manager = ctx.from
						collectorSlots ++= configuration.collectors.map(c => c._2.name -> c._1).toMap
						collectorChannels ++= configuration.collectorOps.map(chOps => chOps.ch.name -> defaultSink(collectorSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self)).toMap
						collectorChannelListeners = configuration.collectorOps.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)
						dischargeSlot = configuration.discharge._1
						dischargeChannel = defaultSource(dischargeSlot, manager, configuration.dischargeOps, ctx.aCtx.self)
						dischargeChannelListener = configuration.dischargeOps.start.ackReceiver
						ctx.reply(FanIn.CompletedConfiguration(ctx.aCtx.self))
						idle
				}
			}
		}
	}

	private lazy val idle: FanIn.RUNNER = collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
		ctx: FanIn.CTX => {
			case cmd @ Transfer(from) => // Load is already waiting
				implicit val c = cmd
				implicit val r =  ctx.from
				val fromChEp = collectorChannels(from)
				val fromChLoc = collectorSlots(from)
				ctx.signal(configuration.carriage, Carriage.GoTo(collectorSlots(from)))
				fetching(fetchSuccessCtx => {
					fetchSuccessCtx.signal(configuration.carriage, Carriage.GoTo(dischargeSlot))
					delivering(
						deliverSuccessCtx => doDischarge(deliverSuccessCtx),
						deliverFailCtx => waitingForSlot)
				},
					fetchFailCtx => waitingForLoad(fromChEp, fromChLoc)(
						loadReceivedCtx => {
							loadReceivedCtx.signal(configuration.carriage, Carriage.GoTo(dischargeSlot));
							delivering(
								deliveringSuccesCtx => doDischarge(deliveringSuccesCtx),
								_ => waitingForSlot
							)
						}
					))
		}
	}

	private def fetching(success: DelayedDomainRun[LiftAssemblySignal],
	                     fail: DelayedDomainRun[LiftAssemblySignal])(implicit cmd: FanInCommand, requester: Processor.Ref): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
			ctx: FanIn.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					ctx.signal(configuration.carriage, Carriage.Load(destination))
					loading(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					fail(ctx)
				case other: CarriageNotification =>
					fail(ctx)
			}
	}

	private def delivering(success: DelayedDomainRun[LiftAssemblySignal],
	                     fail: DelayedDomainRun[LiftAssemblySignal])(implicit cmd: FanInCommand, requester: Processor.Ref): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
			implicit ctx: FanIn.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					ctx.signal(configuration.carriage, Carriage.Unload(destination))
					unloading(success)
				case other: CarriageNotification =>
					//ctx.signal(requester,FanIn.FailedFull(cmd, s"Could not unload tray: $other"))
					fail(ctx)
			}
	}

	private def doDischarge(ctx: FanIn.CTX)(implicit cmd: FanInCommand, requester: Processor.Ref): FanIn.RUNNER = {
		if (dischargeChannel.send(dischargeSlot.inspect.head)(ctx)) dischargeSlot.retrieve
		ctx.signal(requester, CompletedCommand(cmd))
		idle
	}

	private def waitingForSlot(implicit cmd: FanInCommand, requester: Processor.Ref): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse {
			ctx: FanIn.CTX => {
					case signal@SlotBecomesAvailable(slot) if slot == dischargeSlot =>
						ctx.signal(configuration.carriage, Carriage.Unload(slot))
						unloading(afterUnloadingCtx => doDischarge(afterUnloadingCtx))
				}
		}

	private def unloading(continue: DelayedDomainRun[LiftAssemblySignal]): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
			implicit ctx: FanIn.CTX => {
				case Carriage.Unloaded(Carriage.Unload(loc), load) =>
					continue(ctx)
			}
		}


	private def waitingForLoad(from: Channel.End[MaterialLoad, LiftAssemblySignal], fromLoc: => Carriage.Slot)(
		continue: DelayedDomainRun[LiftAssemblySignal]): FanIn.RUNNER =
		dischargeChannelListener orElse {
			ctx: FanIn.CTX => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
					from.performReceiving(tr.load, tr.resource)(ctx)
					if (fromLoc isEmpty) {
						from.get(tr.load)(ctx)
						fromLoc store tr.load
						ctx.signal(configuration.carriage, Carriage.Load(fromLoc))
						loading(continue)
					} else {
						throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
			}
		} orElse collectorChannelListeners orElse ignoreSlotAvailable

	private def loading(continue: DelayedDomainRun[LiftAssemblySignal]): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
			implicit ctx: FanIn.CTX => {
				case Carriage.Loaded(Carriage.Load(loc)) => continue(ctx)
			}
		}

	private def errorLoaded(implicit sourceCmd: FanInCommand, requester: Processor.Ref): FanIn.RUNNER =
		collectorChannelListeners orElse dischargeChannelListener orElse ignoreSlotAvailable orElse {
			ctx: FanIn.CTX => {
				case cmd @ OutputFromTray=>
					ctx.signal(configuration.carriage, Carriage.GoTo(dischargeSlot))
					delivering(afterDeliveryCtx => doDischarge(afterDeliveryCtx),
						_ => waitingForSlot
					)
			}
		}

}
