/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.{DelayedDomainRun, Ref, SignallingContext}
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
	case class OutputFromTray(override val to: String) extends OutboundCommand
	case class Groom(from: SlotLocator, to: SlotLocator) extends ExternalCommand
	case class PutawayFromTray(to: SlotLocator) extends ExternalCommand

	sealed abstract class Notification extends Identification.Impl with ShuttleLevelSignal
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Notification
	case class FailedFull(cmd: ExternalCommand, reason: String) extends Notification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Notification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification

	case class Configuration[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(depth: Int,
	 inbound: Seq[Channel[MaterialLoad, UpstreamMessageType, ShuttleLevelSignal]],
	 outbound: Seq[Channel[MaterialLoad, ShuttleLevelSignal, DownstreamMessageType]]) {
		val inboundOps = inbound.map(new Channel.Ops(_))
		val outboundOps = outbound.map(new Channel.Ops(_))
	}
	case class InitialState(position: SlotLocator, inventory: Map[SlotLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(
		name: String,
		shuttle: Processor[Carriage.CarriageSignal],
		configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
		initial: InitialState)(implicit clockRef: Clock.ClockRef, simController: SimulationController.ControllerRef) = {
		val domain = new ShuttleLevel(name, shuttle, configuration, initial)
		new Processor[ShuttleLevelSignal](name, clockRef, simController, domain.configurer)
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

	private def defaultSink(chOps: Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleLevelSignal], host: Ref) = {
		(new Channel.Sink[MaterialLoad, ShuttleLevelSignal] {
			override val ref: Ref = host
			private lazy val slot = inboundSlots(chOps.ch.name)

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: SignallingContext[ShuttleLevelSignal]): ShuttleLevel.RUNNER = {
				log.debug(s"DefaultSink: Received load $load at channel ${endpoint.channelName}")
				ctx.signal(manager, ShuttleLevel.LoadArrival(endpoint.channelName, load))
				if (slot.isEmpty) {
					log.debug(s"DefaultSink: Pulling load $load into slot $slot")
					endpoint.get(load).foreach(t => slot.store(t._1))
				} else {
					log.debug(s"DefaultSink: Slot $slot is full, leaving load $load in Channel ${endpoint.channelName}")
				}
				log.debug(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelSignal]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad, at: Option[Distance])(implicit ctx: SignallingContext[ShuttleLevelSignal]): ShuttleLevel.RUNNER = {
				if (slot.isEmpty) endpoint.getNext.foreach(t => slot.store(t._1))
				log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelSignal]
			}

			val end = chOps.registerEnd(this)
		}).end
	}

	private def defaultSource(chOps: Channel.Ops[MaterialLoad, ShuttleLevelSignal, DownstreamMessageType], host: Ref) = {
		(new Channel.Source[MaterialLoad, ShuttleLevelSignal] {
			override val ref: Ref = host
			private lazy val slot = outboundSlots(chOps.ch.name)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, ShuttleLevelSignal], load: MaterialLoad)(implicit ctx: SignallingContext[ShuttleLevelSignal]): ShuttleLevel.RUNNER = {
				ctx.signal(manager, ShuttleLevel.LoadAcknowledged(endpoint.channelName, load))
				if (slot.inspect.exists(start.send(_))) slot.retrieve
				log.debug(s"Finishing Load Acknowledge at Source for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelSignal]
			}

			val start = chOps.registerStart(this)
		}).start
	}

	private def configurer: Processor.DomainConfigure[ShuttleLevelSignal] = {
		log.debug(s"Setting up initial Configuration for Shuttle Level: $name")
		new Processor.DomainConfigure[ShuttleLevelSignal] {
			override def configure(config: ShuttleLevelSignal)(implicit ctx: SignallingContext[ShuttleLevelSignal]): Processor.DomainMessageProcessor[ShuttleLevelSignal] = {
				config match {
					case cmd@ShuttleLevel.NoConfigure =>
						manager = ctx.from
						_carriage = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
						ctx.configureContext.signal(_carriage, Carriage.Configure(slots(initial.position)))
						inboundChannels ++= configuration.inboundOps.map(chOps => chOps.ch.name -> defaultSink(chOps, ctx.aCtx.self)).toMap
						inboundSlots ++= inboundChannels.zip((-inboundChannels.size until 0)).map(c => c._1._1 -> Slot(OnLeft(c._2))).toMap
						inboundLoadListener = configuration.inboundOps.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)
						outboundChannels ++= configuration.outboundOps.map(chOps => chOps.ch.name -> defaultSource(chOps, ctx.aCtx.self)).toMap
						outboundSlots ++= outboundChannels.zip((-outboundChannels.size until 0)).map(c => c._1._1 -> Slot(OnRight(c._2))).toMap
						outboundLoadListener = configuration.outboundOps.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.aCtx.log.debug(s"Configured Subcomponents, now waiting for Carriage Confirmation")
						waitingForCarriageConfiguration
				}
			}
		}
	}

	def waitingForCarriageConfiguration(implicit ctx: SignallingContext[ShuttleLevelSignal]) = {
		log.debug(s"Setting up waitingForCarriage Configuration for Lift Level: $name")
		new Processor.DomainConfigure[ShuttleLevelSignal] {
			override def configure(config: ShuttleLevelSignal)(implicit ctx: SignallingContext[ShuttleLevelSignal]): Processor.DomainMessageProcessor[ShuttleLevelSignal] = {
				config match {
					case cmd@Carriage.CompleteConfiguration(pr) if pr == _carriage =>
						// This is be needed in the future to signal the manager
						log.debug(s"Completing CarriageLevel Configuration")
						ctx.configureContext.signal(manager, ShuttleLevel.CompletedConfiguration(ctx.aCtx.self))
						idle
				}
			}
		}
	}

	private lazy val idle: ShuttleLevel.RUNNER = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: ShuttleLevel.CTX => {
			case cmd@Store(fromCh, toLocator) =>
				(inboundSlots.get(fromCh), slots.get(toLocator).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						fetching(cmd)(
							(afterFetchingCtx: ShuttleLevel.CTX) => {
								log.debug(s"After successful fetch, going to $toLoc")
								afterFetchingCtx.signal(_carriage, Carriage.GoTo(toLoc))
								delivering(cmd)(afterDeliverCtx => {
									afterDeliverCtx.signal(manager, ShuttleLevel.CompletedCommand(cmd))
									idle
								}, _ => errorLoaded)
							}
						,
							_ => waitingForLoad(inboundChannels(fromCh), fromLoc)(
									(afterLoadingCtx: ShuttleLevel.CTX) => {
										afterLoadingCtx.signal(_carriage, Carriage.GoTo(toLoc));
										delivering(cmd)(afterDeliverCtx => {
											afterDeliverCtx.signal(manager, ShuttleLevel.CompletedCommand(cmd))
											idle
										}, _ =>  errorLoaded)
									}
								)
						)
					case (None, _) =>
						ctx.reply(NotAcceptedCommand(cmd, s"Inbound Channel does not exist"))
						idle
					case (_, None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Destination does not exist or is full"))
						idle
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd"))
						idle
				}
			case cmd@Retrieve(fromLocator, toChName) =>
				(slots.get(fromLocator).filter(!_.isEmpty), outboundSlots.get(toChName).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toChLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						fetching(cmd)(successFetchingCtx => {
							successFetchingCtx.signal(_carriage, Carriage.GoTo(toChLoc))
							delivering(cmd)(postDeliveryCtx => doSendOutbound(toChLoc, toChName, cmd)(postDeliveryCtx),
								postDeliveryErrorCtx => {waitingForSlot(outboundChannels(toChName), toChLoc)(_ => idle)}
							)
						},
							failedFetchingCtx => {
								failedFetchingCtx.signal(manager,ShuttleLevel.FailedEmpty(cmd, s"Was not able to Fetch for $cmd"))
								idle
							}
						)
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"Source or Destination ($other) are incompatible for Retrieve Command: $cmd"))
						idle
				}
			case cmd@Groom(fromLocator, toLocator) =>
				val maybeFromLoc = slots.get(fromLocator).filter(!_.isEmpty)
				val maybeToLoc = slots.get(toLocator).filter(_.isEmpty)
				(maybeFromLoc, maybeToLoc) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(_carriage, Carriage.GoTo(fromLoc))
						fetching(cmd)(afterFetchingCtx => {
							afterFetchingCtx.signal(_carriage, Carriage.GoTo(toLoc))
							delivering(cmd)(afterDeliverCtx => {
								afterDeliverCtx.signal(manager, CompletedCommand(cmd))
								idle
							},
								_ => errorLoaded
							)
						},
							_ => idle
						)
					case (None, Some(toLoc)) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Origin does not exist or is empty"))
						idle
					case (Some(fromLoc), None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Destination does not exist or is full"))
						idle
					case (None, None) =>
						ctx.reply(ShuttleLevel.FailedEmpty(cmd, "Neither Origin or Destination match requirements"))
						idle
				}
			case cmd@LoopBack(fromChName, toChName) =>
				val fromChEp = inboundChannels(fromChName)
				val fromChLoc = inboundSlots(fromChName)
				val toChEp = outboundChannels(toChName)
				val toChLoc = outboundSlots(toChName)
				ctx.signal(_carriage, Carriage.GoTo(fromChLoc))
				fetching(cmd)(afterFetchingCtx => {
					afterFetchingCtx.signal(_carriage, Carriage.GoTo(toChLoc))
					delivering(cmd)(
						postDeliveryCtx => doSendOutbound(toChLoc, toChName, cmd)(postDeliveryCtx),
						_ => waitingForSlot(toChEp, toChLoc)(_ => idle)
					)
				},
					_ => {
						log.debug(s"Waiting for load on ${fromChEp.channelName} to Loopback to ${toChEp.channelName}")
						waitingForLoad(fromChEp, fromChLoc)(
							afterWaitingForLoadCtx => {
								afterWaitingForLoadCtx.signal(_carriage, Carriage.GoTo(toChLoc));
								delivering(cmd)(
									afterDeliveryCtx => doSendOutbound(toChLoc, toChName, cmd)(afterDeliveryCtx),
									_ => errorLoaded
								)
							}
						)
					}
				)
			case cmd @ PutawayFromTray(to) =>
				val toLoc = slots(to)
				ctx.signal(_carriage, Carriage.GoTo(toLoc))
				delivering(cmd)(
					afterDeliveryCtx => {
						afterDeliveryCtx.signal(manager, CompletedCommand(cmd))
						idle
					},
					_ => errorLoaded
					)
		}
	}

	private def doSendOutbound(toChLoc: Slot, toChName: String, cmd: ExternalCommand)(ctx: SignallingContext[ShuttleLevelSignal]): ShuttleLevel.RUNNER = {
		if (toChLoc.retrieve.exists(outboundChannels(toChName).send(_)(ctx))) {
			ctx.signal(manager, CompletedCommand(cmd))
			idle
		}
		else waitingForSlot(outboundChannels(toChName), toChLoc)(_ => idle)
	}


	/* THIS IS PROBABLY WRONG. NEED TO FIX AFTER TESTING SIMILAR IN LIFT ASSEMBLY */
	private def errorLoaded: ShuttleLevel.RUNNER = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: ShuttleLevel.CTX => {
			case cmd @ OutputFromTray(toChannelName) =>
				val outLoc = outboundSlots(toChannelName)
				ctx.signal(_carriage, Carriage.GoTo(outLoc))
				delivering(cmd)(afterDeliveryCtx => doSendOutbound(outLoc, toChannelName, cmd)(afterDeliveryCtx),
					_ => waitingForSlot(outboundChannels(toChannelName), outboundSlots(toChannelName))(_ => idle)
				)
			case cmd @ PutawayFromTray(toLoc) =>
				delivering(cmd)(afterDeliveryCtx => {
					afterDeliveryCtx.signal(manager, CompletedCommand(cmd))
					idle
				},
					_ => errorLoaded
				)
		}
	}

	private def fetching(cmd: ShuttleLevel.ExternalCommand)(
		success: DelayedDomainRun[ShuttleLevelSignal],
		failure: DelayedDomainRun[ShuttleLevelSignal]) =
		inboundLoadListener orElse outboundLoadListener orElse {
			ctx: ShuttleLevel.CTX => {
				case Carriage.Arrived(Carriage.GoTo(destination)) if !destination.isEmpty =>
					ctx.signal(_carriage, Carriage.Load(destination))
					loading(success)
				case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
					failure(ctx)
				case other: CarriageNotification =>
					failure(ctx)
			}
		}

	private def loading(continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		inboundLoadListener orElse outboundLoadListener orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case Carriage.Loaded(Carriage.Load(loc)) =>
					log.debug(s"Finished Loading in $loc")
					continue(ctx)
			}
		}


	private def delivering(cmd: ShuttleLevel.ExternalCommand)(
		success: DelayedDomainRun[ShuttleLevelSignal],
		failure: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		inboundLoadListener orElse outboundLoadListener orElse {
		implicit ctx: ShuttleLevel.CTX => {
			case Carriage.Arrived(Carriage.GoTo(destination)) if destination.isEmpty =>
				ctx.signal(_carriage, Carriage.Unload(destination))
				unloading(success)
			case other: CarriageNotification =>
				ctx.signal(manager,ShuttleLevel.FailedFull(cmd, s"Could not unload tray: $other"))
				failure(ctx)
		}
	}

	private def unloading(continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		inboundLoadListener orElse outboundLoadListener orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case Carriage.Unloaded(Carriage.Unload(loc), load) =>
					continue(ctx)
			}
		}

	private def waitingForLoad(from: Channel.End[MaterialLoad, ShuttleLevelSignal], fromLoc: => Slot)(
		continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		outboundLoadListener orElse {
			ctx: ShuttleLevel.CTX => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
					from.performReceiving(tr.load, tr.resource)(ctx)
					if (fromLoc isEmpty) {
						from.get(tr.load)(ctx)
						fromLoc store tr.load
						ctx.signal(_carriage, Carriage.Load(fromLoc))
						loading(continue)
					} else {
						throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
			}
		} orElse inboundLoadListener

	/* THIS IS PROBABLY WRONG. NEED TO FIX AFTER TESTING SIMILAR IN LIFT ASSEMBLY */
	private def waitingForSlot(to: Channel.Start[MaterialLoad, ShuttleLevelSignal], toLoc: Slot)(
		continue: DelayedDomainRun[ShuttleLevelSignal]): ShuttleLevel.RUNNER =
		inboundLoadListener orElse {
			implicit ctx: ShuttleLevel.CTX => {
				case tr: Channel.AcknowledgeLoad[MaterialLoad] if tr.channel == to.channelName =>
					ctx.signal(_carriage, Carriage.Unload(toLoc))
					unloading {
						toLoc.retrieve.map(to.send(_))
						continue
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for Lift to arrive to delivery location")
			}
		} orElse outboundLoadListener
}
