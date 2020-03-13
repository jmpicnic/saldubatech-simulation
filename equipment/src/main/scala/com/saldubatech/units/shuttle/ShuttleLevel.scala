/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.{ProcessorRef, SignallingContext}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.shuttle.Shuttle.{LevelLocator, OnLeft, OnRight, ShuttleLocation}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object ShuttleLevel {




	private def inboundLocator(idx: Int): LevelLocator = OnLeft(idx)
	private def outboundLocator(idx: Int): LevelLocator = OnRight(idx)

	trait ShuttleLevelMessage extends Identification

	sealed abstract class ShuttleCommand extends Identification.Impl() with ShuttleLevelMessage
	case object NoConfigure extends ShuttleCommand
	trait ExternalCommand extends ShuttleCommand
	trait InboundCommand extends ExternalCommand {
		val from: String
	}
	trait OutboundCommand extends ExternalCommand {
		val to: String
	}
	case class Store(override val from: String, to: LevelLocator) extends InboundCommand
	case class Retrieve(from: LevelLocator, override val to: String) extends OutboundCommand
	case class LoopBack(override val from: String, override val to: String) extends InboundCommand with OutboundCommand
	case class OutputFromTray(override val to: String) extends OutboundCommand
	case class Groom(from: LevelLocator, to: LevelLocator) extends ExternalCommand
	case class PutawayFromTray(to: LevelLocator) extends ExternalCommand

	sealed trait ShuttleLevelNotification extends ShuttleLevelMessage
	sealed trait ExternalNotification extends ShuttleLevelNotification
	case class InternalFailure(sig: ShuttleLevelMessage, reasong: String) extends Identification.Impl() with ExternalNotification
	case class FailedEmpty(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class FailedFull(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class FailedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with ExternalNotification
	case class LoadArrival(fromCh: String, load: MaterialLoad) extends Identification.Impl() with ExternalNotification
	case class LoadAcknowledged(fromCh: String, load: MaterialLoad) extends Identification.Impl() with ExternalNotification
	case class CompletedConfiguration(self: Processor.ProcessorRef) extends Identification.Impl() with ExternalNotification


	case class Mission(source: Processor.ProcessorRef, cmd: ExternalCommand, at: Clock.Tick)
	sealed trait InternalCommand extends ShuttleCommand
	case class Fetch(from: LevelLocator, mission: Mission) extends InternalCommand
	case class Drop(to: LevelLocator, mission: Mission) extends InternalCommand
	case class Deliver(ch: Channel.Start[MaterialLoad, ShuttleLevelMessage], onSlot: String, mission: Mission) extends InternalCommand
	case class Receive(ch: Channel.End[MaterialLoad, ShuttleLevelMessage], mission: Mission) extends InternalCommand

	sealed trait MaterialMovement extends ShuttleLevelMessage
	case class AcknowledgeLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
		extends Channel.AckLoadImpl[MaterialLoad](channel, load, resource)
		with MaterialMovement
	case class TransferLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
			extends Channel.TransferLoadImpl[MaterialLoad](channel, load, resource)
			with MaterialMovement
	case class PullLoad(override val load: MaterialLoad, override val idx: Int, override val channel: String)
		extends Channel.PulledLoadImpl[MaterialLoad](load, idx, channel)
			with MaterialMovement

	case class Configuration[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](depth: Int,
	                         inbound: Seq[Channel[MaterialLoad, UpstreamMessageType, ShuttleLevelMessage]],
	                         outbound: Seq[Channel[MaterialLoad, ShuttleLevelMessage, DownstreamMessageType]]) {
		val inboundOps = inbound.map(new Channel.Ops(_))
		val outboundOps = outbound.map(new Channel.Ops(_))
	}
	case class InitialState(position: LevelLocator, inventory: Map[LevelLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage]
	(
		name: String,
		shuttle: Processor[Shuttle.ShuttleSignal],
		configuration: Configuration[UpstreamMessageType, DownstreamMessageType],
		initial: InitialState)(implicit clockRef: Clock.ClockRef, simController: SimulationController.ControllerRef) = {
		val domain = new ShuttleLevel(name, shuttle, configuration, initial)
		new Processor[ShuttleLevelMessage](name, clockRef, simController, domain.configurer)
	}


}

import ShuttleLevel._
class ShuttleLevel[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](name: String,
                                                     shuttleProcessor: Processor[Shuttle.ShuttleSignal],
                                                     configuration: ShuttleLevel.Configuration[UpstreamMessageType, DownstreamMessageType],
                                                     initial: ShuttleLevel.InitialState) extends Identification.Impl(name) with LogEnabled {

	private implicit var manager: Processor.ProcessorRef = null

	private val slots: Map[LevelLocator, Shuttle.ShuttleLocation] = (0 until configuration.depth).flatMap(idx => Seq(OnRight(idx) -> Shuttle.ShuttleLocation(OnRight(idx)), OnLeft(idx) -> Shuttle.ShuttleLocation(OnLeft(idx)))).toMap
	initial.inventory.foreach(e => slots(e._1).store(e._2))

	private val inboundSlots: mutable.Map[String, ShuttleLocation] = mutable.Map.empty
	private val inboundChannels: mutable.Map[String, Channel.End[MaterialLoad, ShuttleLevelMessage]] = mutable.Map.empty
	private var inboundLoadListener: Processor.DomainRun[ShuttleLevelMessage] = null

	private val outboundSlots: mutable.Map[String, ShuttleLocation] = mutable.Map.empty
	private val outboundChannels: mutable.Map[String, Channel.Start[MaterialLoad, ShuttleLevelMessage]] = mutable.Map.empty
	private var outboundLoadListener: Processor.DomainRun[ShuttleLevelMessage] = null

	private var shuttle: ActorRef[Processor.ProcessorMessage] = null

	def shuttleRef = shuttle

	private def defaultSink(chOps: Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleLevelMessage], host: ProcessorRef) = {
		(new Channel.Sink[MaterialLoad, ShuttleLevelMessage] {
			override val ref: ProcessorRef = host
			private lazy val slot = inboundSlots(chOps.ch.name)

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleLevelMessage], load: MaterialLoad, at: Option[Distance] = None)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				log.debug(s"DefaultSink: Received load $load at channel ${endpoint.channelName}")
				ctx.signal(manager, ShuttleLevel.LoadArrival(endpoint.channelName, load))
				if (slot.isEmpty) {
					log.debug(s"DefaultSink: Pulling load $load into slot $slot")
					endpoint.get(load).foreach(t => slot.store(t._1))
				} else {
					log.debug(s"DefaultSink: Slot $slot is full, leaving load $load in Channel ${endpoint.channelName}")
				}
				log.debug(s"Finishing Load Arrived at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelMessage]
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleLevelMessage], load: MaterialLoad, at: Option[Distance])(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				if (slot.isEmpty) endpoint.getNext.foreach(t => slot.store(t._1))
				log.debug(s"Finishing Load Released at Sink for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelMessage]
			}

			val end = chOps.registerEnd(this)
		}).end
	}

	private def defaultSource(chOps: Channel.Ops[MaterialLoad, ShuttleLevelMessage, DownstreamMessageType], host: ProcessorRef) = {
		(new Channel.Source[MaterialLoad, ShuttleLevelMessage] {
			override val ref: ProcessorRef = host
			private lazy val slot = outboundSlots(chOps.ch.name)

			override def loadAcknowledged(endpoint: Channel.Start[MaterialLoad, ShuttleLevelMessage], load: MaterialLoad)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				ctx.signal(manager, ShuttleLevel.LoadAcknowledged(endpoint.channelName, load))
				if (slot.inspect.exists(start.send(_))) slot.retrieve
				log.debug(s"Finishing Load Acknowledge at Source for ${endpoint.channelName}")
				Processor.DomainRun.same[ShuttleLevelMessage]
			}

			val start = chOps.registerStart(this)
		}).start
	}

	private def configurer: Processor.DomainConfigure[ShuttleLevelMessage] = {
		log.debug(s"Setting up initial Configuration for Shuttle Level: $name")
		new Processor.DomainConfigure[ShuttleLevelMessage] {
			override def configure(config: ShuttleLevelMessage)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainMessageProcessor[ShuttleLevelMessage] = {
				config match {
					case cmd@ShuttleLevel.NoConfigure =>
						manager = ctx.from
						shuttle = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
						ctx.configureContext.signal(shuttle, Shuttle.Configure(slots(initial.position)))
						inboundChannels ++= configuration.inboundOps.map(chOps => chOps.ch.name -> defaultSink(chOps, ctx.aCtx.self)).toMap
						inboundSlots ++= inboundChannels.zip((-inboundChannels.size until 0)).map(c => c._1._1 -> ShuttleLocation(OnLeft(c._2))).toMap
						inboundLoadListener = configuration.inboundOps.map(chOps => chOps.end.loadReceiver).reduce((l, r) => l orElse r)
						outboundChannels ++= configuration.outboundOps.map(chOps => chOps.ch.name -> defaultSource(chOps, ctx.aCtx.self)).toMap
						outboundSlots ++= outboundChannels.zip((-outboundChannels.size until 0)).map(c => c._1._1 -> ShuttleLocation(OnRight(c._2))).toMap
						outboundLoadListener = configuration.outboundOps.map(chOps => chOps.start.ackReceiver).reduce((l, r) => l orElse r)
						ctx.aCtx.log.debug(s"Configured Subcomponents, now waiting for Shuttle Confirmation")
						waitingForShuttleConfiguration
				}
			}
		}
	}

	def waitingForShuttleConfiguration(implicit ctx: SignallingContext[ShuttleLevelMessage]) = {
		log.debug(s"Setting up waitingForShuttle Configuration for Shuttle Level: $name")
		new Processor.DomainConfigure[ShuttleLevelMessage] {
			override def configure(config: ShuttleLevelMessage)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainMessageProcessor[ShuttleLevelMessage] = {
				config match {
					case cmd@Shuttle.CompleteConfiguration(pr) if pr == shuttle =>
						// This is be needed in the future to signal the manager
						log.debug(s"Completing ShuttleLevel Configuration")
						ctx.configureContext.signal(manager, ShuttleLevel.CompletedConfiguration(ctx.aCtx.self))
						idle
				}
			}
		}
	}

	type DelayedDomainRun = SignallingContext[ShuttleLevelMessage] => Processor.DomainRun[ShuttleLevelMessage]

	private lazy val idle: Processor.DomainRun[ShuttleLevelMessage] = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case cmd@Store(fromCh, toLocator) =>
				(inboundSlots.get(fromCh), slots.get(toLocator).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
						fetching(cmd)(
							(afterFetchingCtx: Processor.SignallingContext[ShuttleLevelMessage]) => {
								log.debug(s"After successful fetch, going to $toLoc")
								afterFetchingCtx.signal(shuttle, Shuttle.GoTo(toLoc))
								delivering(cmd)(afterDeliverCtx => {
									afterDeliverCtx.signal(manager, ShuttleLevel.CompletedCommand(cmd))
									idle
								}, _ => errorLoaded)
							}
						,
							_ => waitingForLoad(inboundChannels(fromCh), fromLoc)(
									(afterLoadingCtx: Processor.SignallingContext[ShuttleLevelMessage]) => {
										afterLoadingCtx.signal(shuttle, Shuttle.GoTo(toLoc));
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
						ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
						fetching(cmd)(successFetchingCtx => {
							successFetchingCtx.signal(shuttle, Shuttle.GoTo(toChLoc))
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
						ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
						fetching(cmd)(afterFetchingCtx => {
							afterFetchingCtx.signal(shuttle, Shuttle.GoTo(toLoc))
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
				ctx.signal(shuttle, Shuttle.GoTo(fromChLoc))
				fetching(cmd)(afterFetchingCtx => {
					afterFetchingCtx.signal(shuttle, Shuttle.GoTo(toChLoc))
					delivering(cmd)(
						postDeliveryCtx => doSendOutbound(toChLoc, toChName, cmd)(postDeliveryCtx),
						_ => waitingForSlot(toChEp, toChLoc)(_ => idle)
					)
				},
					_ => {
						log.debug(s"Waiting for load on ${fromChEp.channelName} to Loopback to ${toChEp.channelName}")
						waitingForLoad(fromChEp, fromChLoc)(
							afterWaitingForLoadCtx => {
								afterWaitingForLoadCtx.signal(shuttle, Shuttle.GoTo(toChLoc));
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
				ctx.signal(shuttle, Shuttle.GoTo(toLoc))
				delivering(cmd)(
					afterDeliveryCtx => {
						afterDeliveryCtx.signal(manager, CompletedCommand(cmd))
						idle
					},
					_ => errorLoaded
					)
		}
	}

	private def doSendOutbound(toChLoc: ShuttleLocation, toChName: String, cmd: ExternalCommand)(ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		if (toChLoc.retrieve.exists(outboundChannels(toChName).send(_)(ctx))) {
			ctx.signal(manager, CompletedCommand(cmd))
			idle
		}
		else waitingForSlot(outboundChannels(toChName), toChLoc)(_ => idle)
	}

	private def errorLoaded: Processor.DomainRun[ShuttleLevelMessage] = inboundLoadListener orElse outboundLoadListener orElse {
		ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case cmd @ OutputFromTray(toChannelName) =>
				val outLoc = outboundSlots(toChannelName)
				ctx.signal(shuttle, Shuttle.GoTo(outLoc))
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
		success: DelayedDomainRun,
		failure: DelayedDomainRun) =
		inboundLoadListener orElse outboundLoadListener orElse {
			ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
				case Shuttle.Arrived(Shuttle.GoTo(destination)) if !destination.isEmpty =>
					ctx.signal(shuttle, Shuttle.Load(destination))
					loading(success)
				case Shuttle.Arrived(Shuttle.GoTo(destination)) if destination.isEmpty =>
					failure(ctx)
				case other: Shuttle.ShuttleNotification =>
					failure(ctx)
			}
		}

	private def loading(continue: DelayedDomainRun): Processor.DomainRun[ShuttleLevelMessage] =
		inboundLoadListener orElse outboundLoadListener orElse {
			implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
				case Shuttle.Loaded(Shuttle.Load(loc)) =>
					log.debug(s"Finished Loading in $loc")
					continue(ctx)
			}
		}


	private def delivering(cmd: ShuttleLevel.ExternalCommand)(
		success: DelayedDomainRun,
		failure: DelayedDomainRun): Processor.DomainRun[ShuttleLevelMessage] =
		inboundLoadListener orElse outboundLoadListener orElse {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) if destination.isEmpty =>
				ctx.signal(shuttle, Shuttle.Unload(destination))
				unloading(success)
			case other: Shuttle.ShuttleNotification =>
				ctx.signal(manager,ShuttleLevel.FailedFull(cmd, s"Could not unload tray: $other"))
				failure(ctx)
		}
	}

	private def unloading(continue: DelayedDomainRun): Processor.DomainRun[ShuttleLevelMessage] =
		inboundLoadListener orElse outboundLoadListener orElse {
			implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
				case Shuttle.Unloaded(Shuttle.Unload(loc), load) =>
					continue(ctx)
			}
		}

	private def waitingForLoad(from: Channel.End[MaterialLoad, ShuttleLevelMessage], fromLoc: => ShuttleLocation)(
		continue: DelayedDomainRun): Processor.DomainRun[ShuttleLevelMessage] =
		outboundLoadListener orElse {
			ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
				case tr: Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
					from.performReceiving(tr.load, tr.resource)(ctx)
					if (fromLoc isEmpty) {
						from.get(tr.load)(ctx)
						fromLoc store tr.load
						ctx.signal(shuttle, Shuttle.Load(fromLoc))
						loading(continue)
					} else {
						throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
			}
		} orElse inboundLoadListener

	private def waitingForSlot(to: Channel.Start[MaterialLoad, ShuttleLevelMessage], toLoc: ShuttleLocation)(
		continue: DelayedDomainRun): Processor.DomainRun[ShuttleLevelMessage] =
		inboundLoadListener orElse {
			implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
				case tr: Channel.AcknowledgeLoad[MaterialLoad] if tr.channel == to.channelName =>
					ctx.signal(shuttle, Shuttle.Unload(toLoc))
					unloading {
						toLoc.retrieve.map(to.send(_))
						continue
					}
				case other =>
					throw new IllegalArgumentException(s"Unknown signal received $other when waiting for Shuttle to arrive to delivery location")
			}
		} orElse outboundLoadListener
}
