/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.DelayedDomainRun
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.units.carriage.Carriage.{OnLeft, OnRight, Slot, SlotLocator}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object ShuttleLevel {
	trait ShuttleLevelSignal extends Identification

	type CTX = Processor.SignallingContext[ShuttleLevelSignal]
	type RUNNER = Processor.DomainRun[ShuttleLevelSignal]

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with ShuttleLevelSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with ShuttleLevelSignal
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

	sealed abstract class Notification extends Identification.Impl with ShuttleLevelSignal
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Notification
	case class FailedBusy(cmd: ExternalCommand, reason: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	sealed abstract class InternalSignal extends Identification.Impl() with ShuttleLevelSignal
	case class SlotBecomesAvailable(slot: Carriage.Slot) extends InternalSignal

	case class Configuration[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(depth: Int,
	 inbound: Seq[Channel[MaterialLoad, UpstreamMessageType, ShuttleLevelSignal]],
	 outbound: Seq[Channel[MaterialLoad, ShuttleLevelSignal, DownstreamMessageType]]) {
		val inboundOps = inbound.map(new Channel.Ops(_))
		val outboundOps = outbound.map(new Channel.Ops(_))
	}
	case class InitialState(position: SlotLocator, inventory: Map[SlotLocator, MaterialLoad])

	private def defaultSink(inboundSlot: Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, _, ShuttleLevelSignal], host: Processor.Ref) = {
		(new Channel.Sink[MaterialLoad, ShuttleLevelSignal] {
			override val ref: Processor.Ref = host

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: CTX): RUNNER = {
				ctx.aCtx.log.debug(s"DefaultSink: Received load $load at channel ${endpoint.channelName}")
				ctx.signal(manager, ShuttleLevel.LoadArrival(endpoint.channelName, load))
				if (inboundSlot.isEmpty) {
					ctx.aCtx.log.debug(s"DefaultSink: Pulling load $load into slot $inboundSlot")
					endpoint.get(load).foreach(t => inboundSlot.store(t._1))
				} else {
					ctx.aCtx.log.debug(s"DefaultSink: Slot $inboundSlot is full, leaving load $load in Channel ${endpoint.channelName}")
				}
				ctx.aCtx.log.debug(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelSignal]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: CTX): RUNNER = {
				if (inboundSlot.isEmpty) endpoint.getNext.foreach(t => inboundSlot.store(t._1))
				ctx.aCtx.log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same
			}

			val end = chOps.registerEnd(this)
		}).end
	}

	private def defaultSource(slot: Carriage.Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, ShuttleLevelSignal, _], host: Processor.Ref) = {
		(new Channel.Source[MaterialLoad, ShuttleLevelSignal] {
			override val ref: Processor.Ref = host
			val start = chOps.registerStart(this)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad)(implicit ctx: ShuttleLevel.CTX): RUNNER = {
				if (slot.inspect nonEmpty) ctx.signalSelf(SlotBecomesAvailable(slot))
				Processor.DomainRun.same
			}
		}).start
	}

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(
		name: String,
		shuttle: Processor[Carriage.CarriageSignal],
		configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
		initial: InitialState)(implicit clockRef: Clock.ClockRef, simController: SimulationController.ControllerRef) = {
		val domain = new ShuttleLevel(name, shuttle, configuration, initial)
		new Processor[ShuttleLevelSignal](name, clockRef, simController, domain.configurer)
	}

	private val ignoreSlotAvailable: RUNNER = Processor.DomainRun {
		case SlotBecomesAvailable(_) =>	Processor.DomainRun.same
	}
	private val rejectExternalCommand: RUNNER = (ctx: CTX) => {
		case cmd: ExternalCommand =>
			ctx.reply(FailedBusy(cmd, "Command cannot be processed. Processor is Busy"))
			Processor.DomainRun.same
	}
}

import ShuttleLevel._
class ShuttleLevel[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](name: String,
                                                                                                                                                          shuttleProcessor: Processor[Carriage.CarriageSignal],
                                                                                                                                                          configuration: ShuttleLevel.Configuration[UpstreamMessageType, DownstreamMessageType],
                                                                                                                                                          initial: ShuttleLevel.InitialState) extends Identification.Impl(name) with LogEnabled {

	private var manager: Processor.Ref = null

	private val slots: Map[SlotLocator, Slot] = (0 until configuration.depth).flatMap(idx => Seq(OnRight(idx) -> Slot(OnRight(idx)), OnLeft(idx) -> Slot(OnLeft(idx)))).toMap
	initial.inventory.foreach(e => slots(e._1).store(e._2))

	private val inboundSlots: mutable.Map[String, Slot] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, Channel.End[MaterialLoad, ShuttleLevelSignal]] = mutable.Map.empty
	private var inboundLoadListener: ShuttleLevel.RUNNER = null

	private val outboundSlots: mutable.Map[String, Slot] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, Channel.Start[MaterialLoad, ShuttleLevelSignal]] = mutable.Map.empty
	private var outboundLoadListener: ShuttleLevel.RUNNER = null

	private var _carriage: ActorRef[Processor.ProcessorMessage] = null

	def carriageRef = _carriage

	private def configurer: Processor.DomainConfigure[ShuttleLevelSignal] = {
		log.debug(s"Setting up initial Configuration for Shuttle Level: $name")
		new Processor.DomainConfigure[ShuttleLevelSignal] {
			override def configure(config: ShuttleLevelSignal)(implicit ctx: ShuttleLevel.CTX): Processor.DomainMessageProcessor[ShuttleLevelSignal] = {
				config match {
					case cmd@ShuttleLevel.NoConfigure =>
						manager = ctx.from
						_carriage = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
						ctx.configureContext.signal(_carriage, Carriage.Configure(slots(initial.position)))
						inboundSlots ++= configuration.inboundOps.zip(-configuration.inboundOps.size until 0).map(t => t._1.ch.name -> Slot(OnLeft(t._2)))
						inboundChannels ++= configuration.inboundOps.map(chOps => chOps.ch.name -> defaultSink(inboundSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self)).toMap
						inboundLoadListener = configuration.inboundOps.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outboundOps.zip(-configuration.outboundOps.size until 0).map{c => c._1.ch.name -> Slot(OnRight(c._2))}
						outboundChannels ++= configuration.outboundOps.map(chOps => chOps.ch.name -> defaultSource(outboundSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self)).toMap
						outboundLoadListener = configuration.outboundOps.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.aCtx.log.debug(s"Configured Subcomponents, now waiting for Carriage Confirmation")
						waitingForCarriageConfiguration
				}
			}
		}
	}

	def waitingForCarriageConfiguration(implicit ctx: ShuttleLevel.CTX) = {
		log.debug(s"Setting up waitingForCarriage Configuration for Lift Level: $name")
		new Processor.DomainConfigure[ShuttleLevelSignal] {
			override def configure(config: ShuttleLevelSignal)(implicit ctx: ShuttleLevel.CTX): Processor.DomainMessageProcessor[ShuttleLevelSignal] = {
				config match {
					case cmd@Carriage.CompleteConfiguration(pr) if pr == _carriage =>
						// This is be needed in the future to signal the manager
						log.debug(s"Completing CarriageLevel Configuration")
						ctx.configureContext.signal(manager, ShuttleLevel.CompletedConfiguration(ctx.aCtx.self))
						IDLE
				}
			}
		}
	}

	private lazy val IDLE: ShuttleLevel.RUNNER =
		inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
		ctx: ShuttleLevel.CTX => {
			case cmd@Store(fromCh, toLocator) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				(inboundSlots.get(fromCh), slots.get(toLocator).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						FETCHING(
							(successFetchingCtx: ShuttleLevel.CTX) => {
								successFetchingCtx.signal(_carriage, Carriage.GoTo(toLoc))
								DELIVERING(successDeliverCtx => {
									successDeliverCtx.signal(manager, ShuttleLevel.CompletedCommand(cmd))
									IDLE
								},
									_ => ERROR_LOADED)
							}
						,
							_ => WAITING_FOR_LOAD(inboundChannels(fromCh), fromLoc)(
									(afterLoadingCtx: ShuttleLevel.CTX) => {
										afterLoadingCtx.signal(_carriage, Carriage.GoTo(toLoc));
										DELIVERING(afterDeliverCtx => {
											afterDeliverCtx.signal(manager, ShuttleLevel.CompletedCommand(cmd))
											IDLE
										},
											_ =>  ERROR_LOADED)
									}
								)
						)
					case (None, _) =>
						ctx.reply(NotAcceptedCommand(cmd, s"Inbound Channel does not exist"))
						IDLE
					case (_, None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Destination does not exist or is full"))
						IDLE
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd"))
						IDLE
				}
			case cmd@Retrieve(fromLocator, toChName) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				(slots.get(fromLocator).filter(!_.isEmpty), outboundSlots.get(toChName).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toChLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						val toEp = outboundChannels(toChName)
						FETCHING(
							successFetchingCtx => {
								successFetchingCtx.signal(_carriage, Carriage.GoTo(toChLoc))
								DELIVERING(postDeliveryCtx => doDischarge(toChLoc, toEp)(postDeliveryCtx),
									postDeliveryErrorCtx => WAITING_FOR_SLOT(toEp, toChLoc)
								)
							},
							failedFetchingCtx => {
								failedFetchingCtx.signal(manager,ShuttleLevel.FailedEmpty(cmd, s"Was not able to Fetch for $cmd"))
								IDLE
							}
						)
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"Source or Destination ($other) are incompatible for Retrieve Command: $cmd"))
						IDLE
				}
			case cmd@Groom(fromLocator, toLocator) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				val maybeFromLoc = slots.get(fromLocator).filter(!_.isEmpty)
				val maybeToLoc = slots.get(toLocator).filter(_.isEmpty)
				(maybeFromLoc, maybeToLoc) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						FETCHING(afterFetchingCtx => {
							afterFetchingCtx.signal(_carriage, Carriage.GoTo(toLoc))
							DELIVERING(afterDeliverCtx => {
								afterDeliverCtx.signal(manager, CompletedCommand(cmd))
								IDLE
							},
								_ => ERROR_LOADED)
						},
							_ => IDLE
						)
					case (None, Some(toLoc)) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Origin does not exist or is empty"))
						IDLE
					case (Some(fromLoc), None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Destination does not exist or is full"))
						IDLE
					case (None, None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Neither Origin or Destination match requirements"))
						IDLE
				}
			case cmd@LoopBack(fromChName, toChName) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				val fromChEp = inboundChannels(fromChName)
				val fromChLoc = inboundSlots(fromChName)
				val toChEp = outboundChannels(toChName)
				val toChLoc = outboundSlots(toChName)
				ctx.signal(_carriage, Carriage.GoTo(fromChLoc))
				FETCHING(fetchSuccessCtx => {
					fetchSuccessCtx.signal(_carriage, Carriage.GoTo(toChLoc))
					DELIVERING(
						postDeliveryCtx => doDischarge(toChLoc, toChEp)(postDeliveryCtx),
						_ => WAITING_FOR_SLOT(toChEp, toChLoc))
				},
					_ => WAITING_FOR_LOAD(fromChEp, fromChLoc)(
						loadReceivedCtx => {
							loadReceivedCtx.signal(_carriage, Carriage.GoTo(toChLoc));
								DELIVERING(
									deliveringSuccessCtx => doDischarge(toChLoc, toChEp)(deliveringSuccessCtx),
									_ => WAITING_FOR_SLOT(toChEp, toChLoc)
								)
							}
						))
		}
	}

	private def doDischarge(toChLoc: Slot, toEp: Channel.Start[MaterialLoad, ShuttleLevelSignal])(ctx: ShuttleLevel.CTX)(implicit cmd: ExternalCommand, requester: Processor.Ref): ShuttleLevel.RUNNER =
		if(toChLoc.inspect.isEmpty) throw new IllegalStateException(s"Unexpected Empty Discharge slot while executing $cmd for $requester")
		else if (toChLoc.inspect.exists(toEp.send(_)(ctx))) {
			toChLoc.retrieve
			ctx.signal(requester, CompletedCommand(cmd))
			IDLE
		} else WAITING_FOR_SLOT(toEp, toChLoc)

	private def FETCHING(
		success: DelayedDomainRun[ShuttleLevelSignal],
		failure: DelayedDomainRun[ShuttleLevelSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			ctx: ShuttleLevel.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					ctx.signal(_carriage, Carriage.Load(destination))
					LOADING(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					failure(ctx)
				case other: CarriageNotification =>
					failure(ctx)
			}
		}

	private def DELIVERING(success: DelayedDomainRun[ShuttleLevelSignal],
		failure: DelayedDomainRun[ShuttleLevelSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					ctx.signal(_carriage, Carriage.Unload(destination))
					UNLOADING(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					failure(ctx)
				case other: CarriageNotification =>
					failure(ctx)
			}
		}

	private def WAITING_FOR_LOAD(from: Channel.End[MaterialLoad, ShuttleLevelSignal], fromLoc: => Slot)(
		continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse outboundLoadListener orElse {
			ctx: ShuttleLevel.CTX => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
					from.performReceiving(tr.load, tr.resource)(ctx)
					if (fromLoc isEmpty) {
						from.get(tr.load)(ctx)
						fromLoc store tr.load
						ctx.signal(_carriage, Carriage.Load(fromLoc))
						LOADING(continue)
					} else {
						throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
			}
		} orElse inboundLoadListener orElse ignoreSlotAvailable

	private def LOADING(continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case Carriage.Loaded(Carriage.Load(loc)) => continue(ctx)
			}
		}

	private def WAITING_FOR_SLOT(toEp: Channel.Start[MaterialLoad, ShuttleLevelSignal], toLoc: Slot)(implicit cmd: ExternalCommand, requester: Processor.Ref): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse {
			ctx: ShuttleLevel.CTX => {
				case signal@SlotBecomesAvailable(slot) if slot == toLoc =>
					if (slot.isEmpty) {
						log.info(s"DISCHARGE SLOT: EMPTY")
						IDLE
					}
					else {
						log.info(s"DISCHARGE SLOT: FULL ${toLoc.inspect}")
						doDischarge(toLoc, toEp)(ctx)
					}
			}
		}

	private def UNLOADING(continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case Carriage.Unloaded(Carriage.Unload(loc), load) => continue(ctx)
			}
		}

	private def ERROR_LOADED(implicit extCmd: ExternalCommand, requester: Processor.Ref): ShuttleLevel.RUNNER = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: ShuttleLevel.CTX => {
			case cmd @ PutawayFromTray(to) =>
				val toLoc = slots(to)
				ctx.signal(_carriage, Carriage.GoTo(toLoc))
				DELIVERING(
					afterDeliveryCtx => {
						afterDeliveryCtx.signal(manager, CompletedCommand(cmd))
						IDLE
					},
					_ => ERROR_LOADED
				)
			case cmd @ DeliverFromTray(chName) =>
				val toLoc = outboundSlots(chName)
				val toEp = outboundChannels(chName)
				ctx.signal(_carriage, Carriage.GoTo(toLoc))
				DELIVERING(
					successDeliveryCtx => doDischarge(toLoc, toEp)(successDeliveryCtx),
					failDeliveryCtx => WAITING_FOR_SLOT(toEp, toLoc)
				)
		}
	}
}
