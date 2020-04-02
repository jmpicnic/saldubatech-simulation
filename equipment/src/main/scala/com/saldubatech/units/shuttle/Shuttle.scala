/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.DelayedDomainRun
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.units.carriage.Carriage.{OnLeft, OnRight, Slot, SlotLocator}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object Shuttle {
	trait ShuttleSignal extends Identification

	type CTX = Processor.SignallingContext[ShuttleSignal]
	type RUNNER = Processor.DomainRun[ShuttleSignal]

	sealed abstract class ShuttleLevelConfigurationCommand extends Identification.Impl() with ShuttleSignal
	case object NoConfigure extends ShuttleLevelConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with ShuttleSignal
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

	sealed abstract class Notification extends Identification.Impl with EquipmentManager.Notification
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Notification
	case class FailedBusy(cmd: ExternalCommand, reason: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	sealed abstract class InternalSignal extends Identification.Impl() with ShuttleSignal
	case class SlotBecomesAvailable(slot: Carriage.Slot) extends InternalSignal

	case class Configuration[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 depth: Int,
	 physics: Carriage.CarriageTravel,
	 inbound: Seq[Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleSignal]],
	 outbound: Seq[Channel.Ops[MaterialLoad, ShuttleSignal, DownstreamMessageType]])

	case class InitialState(position: Int, inventory: Map[SlotLocator, MaterialLoad])

	private def inductSink[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage](inboundSlot: Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleSignal], host: Processor.Ref) = {
		(new Channel.Sink[MaterialLoad, ShuttleSignal] {
			override val ref: Processor.Ref = host

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleSignal], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: CTX): RUNNER = {
				ctx.aCtx.log.debug(s"DefaultSink: Received load $load at channel ${endpoint.channelName}")
				ctx.signal(manager, Shuttle.LoadArrival(endpoint.channelName, load))
				if (inboundSlot.isEmpty) {
					ctx.aCtx.log.debug(s"DefaultSink: Pulling load $load into slot $inboundSlot")
					endpoint.get(load).foreach(t => inboundSlot.store(t._1))
				} else {
					ctx.aCtx.log.debug(s"DefaultSink: Slot $inboundSlot is full, leaving load $load in Channel ${endpoint.channelName}")
				}
				ctx.aCtx.log.debug(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleSignal]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: CTX): RUNNER = {
				if (inboundSlot.isEmpty) endpoint.getNext.foreach(t => inboundSlot.store(t._1))
				ctx.aCtx.log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same
			}

			val end = chOps.registerEnd(this)
		}).end
	}

	private def dischargeSource[DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(slot: Carriage.Slot, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, ShuttleSignal, DownstreamMessageType], host: Processor.Ref) = {
		(new Channel.Source[MaterialLoad, ShuttleSignal] {
			override val ref: Processor.Ref = host
			val start = chOps.registerStart(this)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, ShuttleSignal], load: MaterialLoad)(implicit ctx: Shuttle.CTX): RUNNER = {
				if (slot.inspect nonEmpty) ctx.signalSelf(SlotBecomesAvailable(slot))
				Processor.DomainRun.same
			}
		}).start
	}

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
		initial: InitialState)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref) = {
		val carriage = Carriage.buildProcessor(configuration.name+"_carriage", configuration.physics, clockRef, simController)
		val domain = new Shuttle(configuration.name, carriage, configuration, initial)
		new Processor[ShuttleSignal](configuration.name, clockRef, simController, domain.configurer)
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

import Shuttle._
class Shuttle[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
(name: String,
 shuttleProcessor: Processor[Carriage.CarriageSignal],
 configuration: Shuttle.Configuration[UpstreamMessageType, DownstreamMessageType],
 initial: Shuttle.InitialState) extends Identification.Impl(name) with LogEnabled {

	private var manager: Processor.Ref = null

	private val slots: Map[SlotLocator, Slot] = (0 until configuration.depth).flatMap(idx => Seq(OnRight(idx) -> Slot(OnRight(idx)), OnLeft(idx) -> Slot(OnLeft(idx)))).toMap
	initial.inventory.foreach(e => slots(e._1).store(e._2))

	private val inboundSlots: mutable.Map[String, Slot] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, Channel.End[MaterialLoad, ShuttleSignal]] = mutable.Map.empty
	private var inboundLoadListener: Shuttle.RUNNER = null

	private val outboundSlots: mutable.Map[String, Slot] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, Channel.Start[MaterialLoad, ShuttleSignal]] = mutable.Map.empty
	private var outboundLoadListener: Shuttle.RUNNER = null

	private var carriage: ActorRef[Processor.ProcessorMessage] = null


	private def configurer: Processor.DomainConfigure[ShuttleSignal] = {
		new Processor.DomainConfigure[ShuttleSignal] {
			override def configure(config: ShuttleSignal)(implicit ctx: Shuttle.CTX): Processor.DomainMessageProcessor[ShuttleSignal] = {
				config match {
					case cmd@Shuttle.NoConfigure =>
						manager = ctx.from
						carriage = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
						ctx.configureContext.signal(carriage, Carriage.Configure(initial.position))
						inboundSlots ++= configuration.inbound.zip(-configuration.inbound.size until 0).map(t => t._1.ch.name -> Slot(OnLeft(t._2)))
						inboundChannels ++= configuration.inbound.map{chOps =>	chOps.ch.name -> inductSink(inboundSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self)}.toMap
						inboundLoadListener = configuration.inbound.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)

						outboundSlots ++= configuration.outbound.zip(-configuration.outbound.size until 0).map{c => c._1.ch.name -> Slot(OnRight(c._2))}
						outboundChannels ++= configuration.outbound.map(chOps => chOps.ch.name -> dischargeSource(outboundSlots(chOps.ch.name), manager, chOps, ctx.aCtx.self)).toMap
						outboundLoadListener = configuration.outbound.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.aCtx.log.debug(s"Configured Subcomponents, now waiting for Carriage Confirmation")
						waitingForCarriageConfiguration
				}
			}
		}
	}

	def waitingForCarriageConfiguration(implicit ctx: Shuttle.CTX) = {
		log.debug(s"Setting up waitingForCarriage Configuration for Lift Level: $name")
		new Processor.DomainConfigure[ShuttleSignal] {
			override def configure(config: ShuttleSignal)(implicit ctx: Shuttle.CTX): Processor.DomainMessageProcessor[ShuttleSignal] = {
				config match {
					case cmd@Carriage.CompleteConfiguration(pr) if pr == carriage =>
						// This is be needed in the future to signal the manager
						log.debug(s"Completing CarriageLevel Configuration")
						ctx.configureContext.signal(manager, Shuttle.CompletedConfiguration(ctx.aCtx.self))
						IDLE
				}
			}
		}
	}

	private lazy val IDLE: Shuttle.RUNNER =
		inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
		ctx: Shuttle.CTX => {
			case cmd@Store(fromCh, toLocator) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				(inboundSlots.get(fromCh), slots.get(toLocator).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(carriage, Carriage.GoTo(fromLoc))
						FETCHING(
							(successFetchingCtx: Shuttle.CTX) => {
								successFetchingCtx.signal(carriage, Carriage.GoTo(toLoc))
								DELIVERING(successDeliverCtx => {
									successDeliverCtx.signal(manager, Shuttle.CompletedCommand(cmd))
									IDLE
								},
									_ => ERROR_LOADED)
							}
						,
							_ => WAITING_FOR_LOAD(inboundChannels(fromCh), fromLoc)(
									(afterLoadingCtx: Shuttle.CTX) => {
										afterLoadingCtx.signal(carriage, Carriage.GoTo(toLoc));
										DELIVERING(afterDeliverCtx => {
											afterDeliverCtx.signal(manager, Shuttle.CompletedCommand(cmd))
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
						ctx.reply(Shuttle.FailedEmpty(cmd, "Destination does not exist or is full"))
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
						ctx.signal(carriage, Carriage.GoTo(fromLoc))
						val toEp = outboundChannels(toChName)
						FETCHING(
							successFetchingCtx => {
								successFetchingCtx.signal(carriage, Carriage.GoTo(toChLoc))
								DELIVERING(postDeliveryCtx => doDischarge(toChLoc, toEp)(postDeliveryCtx),
									postDeliveryErrorCtx => WAITING_FOR_SLOT(toEp, toChLoc)
								)
							},
							failedFetchingCtx => {
								failedFetchingCtx.signal(manager,Shuttle.FailedEmpty(cmd, s"Was not able to Fetch for $cmd"))
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
						ctx.signal(carriage, Carriage.GoTo(fromLoc))
						FETCHING(afterFetchingCtx => {
							afterFetchingCtx.signal(carriage, Carriage.GoTo(toLoc))
							DELIVERING(afterDeliverCtx => {
								afterDeliverCtx.signal(manager, CompletedCommand(cmd))
								IDLE
							},
								_ => ERROR_LOADED)
						},
							_ => IDLE
						)
					case (None, Some(toLoc)) =>
						ctx.reply(Shuttle.FailedEmpty(cmd, "Origin does not exist or is empty"))
						IDLE
					case (Some(fromLoc), None) =>
						ctx.reply(Shuttle.FailedEmpty(cmd, "Destination does not exist or is full"))
						IDLE
					case (None, None) =>
						ctx.reply(Shuttle.FailedEmpty(cmd, "Neither Origin or Destination match requirements"))
						IDLE
				}
			case cmd@LoopBack(fromChName, toChName) =>
				implicit val c = cmd
				implicit val r =  ctx.from
				val fromChEp = inboundChannels(fromChName)
				val fromChLoc = inboundSlots(fromChName)
				val toChEp = outboundChannels(toChName)
				val toChLoc = outboundSlots(toChName)
				ctx.signal(carriage, Carriage.GoTo(fromChLoc))
				FETCHING(fetchSuccessCtx => {
					fetchSuccessCtx.signal(carriage, Carriage.GoTo(toChLoc))
					DELIVERING(
						postDeliveryCtx => doDischarge(toChLoc, toChEp)(postDeliveryCtx),
						_ => WAITING_FOR_SLOT(toChEp, toChLoc))
				},
					_ => WAITING_FOR_LOAD(fromChEp, fromChLoc)(
						loadReceivedCtx => {
							loadReceivedCtx.signal(carriage, Carriage.GoTo(toChLoc));
								DELIVERING(
									deliveringSuccessCtx => doDischarge(toChLoc, toChEp)(deliveringSuccessCtx),
									_ => WAITING_FOR_SLOT(toChEp, toChLoc)
								)
							}
						))
		}
	}

	private def doDischarge(toChLoc: Slot, toEp: Channel.Start[MaterialLoad, ShuttleSignal])(ctx: Shuttle.CTX)(implicit cmd: ExternalCommand, requester: Processor.Ref): Shuttle.RUNNER =
		if(toChLoc.inspect.isEmpty) throw new IllegalStateException(s"Unexpected Empty Discharge slot while executing $cmd for $requester")
		else if (toChLoc.inspect.exists(toEp.send(_)(ctx))) {
			toChLoc.retrieve
			ctx.signal(requester, CompletedCommand(cmd))
			IDLE
		} else WAITING_FOR_SLOT(toEp, toChLoc)

	private def FETCHING(
		                    success: DelayedDomainRun[ShuttleSignal],
		                    failure: DelayedDomainRun[ShuttleSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): Shuttle.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			ctx: Shuttle.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					ctx.signal(carriage, Carriage.Load(destination))
					LOADING(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					failure(ctx)
				case other: CarriageNotification =>
					failure(ctx)
			}
		}

	private def DELIVERING(success: DelayedDomainRun[ShuttleSignal],
	                       failure: DelayedDomainRun[ShuttleSignal])(implicit cmd: ExternalCommand, requester: Processor.Ref): Shuttle.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: Shuttle.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					ctx.signal(carriage, Carriage.Unload(destination))
					UNLOADING(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					failure(ctx)
				case other: CarriageNotification =>
					failure(ctx)
			}
		}

	private def WAITING_FOR_LOAD(from: Channel.End[MaterialLoad, ShuttleSignal], fromLoc: => Slot)(
		continue: DelayedDomainRun[ShuttleSignal]): Shuttle.RUNNER =
		rejectExternalCommand orElse outboundLoadListener orElse {
			ctx: Shuttle.CTX => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
					from.performReceiving(tr.load, tr.resource)(ctx)
					if (fromLoc isEmpty) {
						from.get(tr.load)(ctx)
						fromLoc store tr.load
						ctx.signal(carriage, Carriage.Load(fromLoc))
						LOADING(continue)
					} else {
						throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
			}
		} orElse inboundLoadListener orElse ignoreSlotAvailable

	private def LOADING(continue: DelayedDomainRun[ShuttleSignal]): Shuttle.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: Shuttle.CTX => {
				case Carriage.Loaded(Carriage.Load(loc)) => continue(ctx)
			}
		}

	private def WAITING_FOR_SLOT(toEp: Channel.Start[MaterialLoad, ShuttleSignal], toLoc: Slot)(implicit cmd: ExternalCommand, requester: Processor.Ref): Shuttle.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse {
			ctx: Shuttle.CTX => {
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

	private def UNLOADING(continue: DelayedDomainRun[ShuttleSignal]): Shuttle.RUNNER =
		rejectExternalCommand orElse inboundLoadListener orElse outboundLoadListener orElse ignoreSlotAvailable orElse {
			implicit ctx: Shuttle.CTX => {
				case Carriage.Unloaded(Carriage.Unload(loc), load) => continue(ctx)
			}
		}

	private def ERROR_LOADED(implicit extCmd: ExternalCommand, requester: Processor.Ref): Shuttle.RUNNER = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: Shuttle.CTX => {
			case cmd @ PutawayFromTray(to) =>
				val toLoc = slots(to)
				ctx.signal(carriage, Carriage.GoTo(toLoc))
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
				ctx.signal(carriage, Carriage.GoTo(toLoc))
				DELIVERING(
					successDeliveryCtx => doDischarge(toLoc, toEp)(successDeliveryCtx),
					failDeliveryCtx => WAITING_FOR_SLOT(toEp, toLoc)
				)
		}
	}
}
