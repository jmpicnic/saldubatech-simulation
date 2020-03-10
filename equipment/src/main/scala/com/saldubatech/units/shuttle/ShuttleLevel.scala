/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.ddes.Processor.{SignallingContext, ProcessorRef}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport.{Channel, MaterialLoad, ChannelConnections}
import com.saldubatech.units.shuttle.Shuttle.{LevelLocator, OnLeft, OnRight, ShuttleLocation}

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
	case class LoadArrival(fromCh: String) extends Identification.Impl() with ExternalNotification


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
	case class PullLoad(override val load: MaterialLoad, override val idx: Int)
		extends Channel.PulledLoadImpl[MaterialLoad](load, idx)
			with MaterialMovement

	case class Configuration[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](depth: Int,
	                         inbound: Seq[Channel[MaterialLoad, UpstreamMessageType, ShuttleLevelMessage]],
	                         outbound: Seq[Channel[MaterialLoad, ShuttleLevelMessage, DownstreamMessageType]]) {
		val inboundOps = inbound.map(new Channel.Ops(_))
		val outboundOps = outbound.map(new Channel.Ops(_))
	}
	case class InitialState(position: LevelLocator, inventory: Map[LevelLocator, MaterialLoad])

	def buildProcessor[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](name: String, shuttle: Processor[Shuttle.ShuttleSignal], configuration: Configuration[UpstreamMessageType, DownstreamMessageType], initial: InitialState)(implicit clockRef: Clock.ClockRef, simController: SimulationController.ControllerRef) = {
		val domain = new ShuttleLevel(name, shuttle, configuration, initial)
		new Processor[ShuttleLevelMessage](name, clockRef, simController, domain.configurer)
	}


}

import ShuttleLevel._
class ShuttleLevel[UpstreamMessageType >: ChannelConnections.ChannelSourceMessage, DownstreamMessageType >: ChannelConnections.ChannelDestinationMessage](name: String,
                                                     shuttleProcessor: Processor[Shuttle.ShuttleSignal],
                                                     configuration: ShuttleLevel.Configuration[UpstreamMessageType, DownstreamMessageType],
                                                     initial: ShuttleLevel.InitialState)
                                                    (implicit clockRef: ActorRef[ClockMessage], controllerRef: ActorRef[ControllerMessage]) extends Identification.Impl(name) {
	//val inboundChannelOps = configuration.inboundOps
	//val outboundChannelOps = configuration.outboundOps


	private val slots: Map[LevelLocator, Shuttle.ShuttleLocation] = (0 until configuration.depth).flatMap(idx => Seq(OnRight(idx) -> Shuttle.ShuttleLocation(OnRight(idx)), OnLeft(idx) -> Shuttle.ShuttleLocation(OnLeft(idx)))).toMap
	initial.inventory.foreach(e => slots(e._1).store(e._2))

	private val inboundSlots: mutable.Map[String, ShuttleLocation] = mutable.Map.empty
	//private val reverseInboundSlots = mutable.Map.empty //inboundSlots.map(e => e._2 -> e._1)
	private val inboundChannels: mutable.Map[String, Channel.End[MaterialLoad, ShuttleLevelMessage]] = mutable.Map.empty

	private val outboundSlots: mutable.Map[String, ShuttleLocation] = mutable.Map.empty
	//private val reverseOutboundSlots = mutable.Map.empty //inboundSlots.map(e => e._2 -> e._1)
	private val outboundChannels: mutable.Map[String, Channel.Start[MaterialLoad, ShuttleLevelMessage]] = mutable.Map.empty

	private var shuttle: ActorRef[Processor.ProcessorMessage] = null
	def shuttleRef = shuttle

	private def idleSink(chOps: Channel.Ops[MaterialLoad, UpstreamMessageType, ShuttleLevelMessage], host: ProcessorRef) = {
		(new Channel.Sink[MaterialLoad, ShuttleLevelMessage] {
			override val ref: ProcessorRef = host
			private lazy val slot = inboundSlots(chOps.ch.name)

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleLevelMessage], load: MaterialLoad, at: Option[Distance])(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				if(slot.isEmpty) endpoint.get(load).foreach(t => slot.store(t._1))
				idle
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ShuttleLevelMessage], load: MaterialLoad, at: Option[Distance])(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] =  {
				if(slot.isEmpty) endpoint.getNext.foreach(t => slot.store(t._1))
				idle
			}
			val end = chOps.registerEnd(this)
		}).end
	}

	private def idleSource(chOps: Channel.Ops[MaterialLoad, ShuttleLevelMessage, DownstreamMessageType], host: ProcessorRef) = {
		(new Channel.Source[MaterialLoad, ShuttleLevelMessage] {
			override val ref: ProcessorRef = host
			private lazy val slot = outboundSlots(chOps.ch.name)

			override def loadAcknowledged(load: MaterialLoad)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] ={
				if(slot.inspect.exists(start.send(_))) slot.retrieve
				idle
			}
			val start = chOps.registerStart(this)
		}).start
	}

	private lazy val idleLoadHandler: Processor.DomainRun[ShuttleLevelMessage] = (inboundChannels.values.map(_.loadReceiver) ++ outboundChannels.values.map(_.ackReceiver)).reduce((l, r) => l orElse r)

	private val configurer: Processor.DomainConfigure[ShuttleLevelMessage] = new Processor.DomainConfigure[ShuttleLevelMessage] {
		override def configure(config: ShuttleLevelMessage)(implicit ctx: SignallingContext[ShuttleLevelMessage]): Processor.DomainMessageProcessor[ShuttleLevelMessage] = config match {
			case cmd @ ShuttleLevel.NoConfigure =>
				shuttle = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
				ctx.configureContext.signal(shuttle, Shuttle.Configure(slots(initial.position)))
				inboundChannels ++= configuration.inboundOps.map(chOps => chOps.ch.name -> idleSink(chOps, ctx.aCtx.self)).toMap
				inboundSlots ++= inboundChannels.zip((-inboundChannels.size until 0)).map(c => c._1._1 -> ShuttleLocation(OnLeft(c._2))).toSeq
				outboundChannels ++= configuration.outboundOps.map(chOps => chOps.ch.name -> idleSource(chOps, ctx.aCtx.self)).toMap
				inboundSlots ++= inboundChannels.zip((-outboundChannels.size until 0)).map(c => c._1._1 -> ShuttleLocation(OnRight(c._2))).toSeq
				ctx.aCtx.log.debug(s"Configured Subcomponents, now waiting for Shuttle Confirmation")
				this
			case cmd @ Shuttle.CompleteConfiguration(pr) if pr == shuttle =>
				idle
		}
	}

	private val idle: Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] =>
			(idleLoadHandler orElse Processor.DomainRun[ShuttleLevelMessage]{
			case cmd@Store(fromCh, toLocator) =>
				(inboundSlots.get(fromCh), slots.get(toLocator).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toLoc)) =>
						ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
						fetching(cmd) (
							{
								ctx.signal(shuttle, Shuttle.GoTo(toLoc))
								delivering(cmd)(
									{
										ctx.reply(ShuttleLevel.CompletedCommand(cmd))
										idle
									},
									{
										errorLoaded
									}
								)
							},
							waitingForLoad(inboundChannels(fromCh), fromLoc) {
								ctx.signal(shuttle, Shuttle.GoTo(toLoc));
								delivering(cmd)(
									{
										ctx.reply(ShuttleLevel.CompletedCommand(cmd))
										idle
									},
									{
										errorLoaded
									}
								)
							}
						)
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"From or To ($other) are incompatible for Store Command: $cmd"))
						idle
				}
			case cmd@Retrieve(fromLocator, toChName) =>
				val toLoc = outboundSlots(toChName)
				(slots.get(fromLocator).filter(!_.isEmpty), outboundSlots.get(toChName).filter(_.isEmpty)) match {
					case (Some(fromLoc), Some(toChLoc)) =>
						ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
						fetching(cmd)(
							{
								ctx.signal(shuttle, Shuttle.GoTo(toLoc))
								delivering(cmd)({
									if (toChLoc.retrieve.exists(outboundChannels(toChName).send(_))) {
										ctx.reply(CompletedCommand(cmd))
										idle
									} else waitingForSlot(outboundChannels(toChName), toChLoc)(idle)
								},
									waitingForSlot(outboundChannels(toChName), toChLoc)(idle)
								)
							},
							idle
						)
					case other =>
						ctx.reply(NotAcceptedCommand(cmd, s"From or To ($other) are incompatible for Retrieve Command: $cmd"))
						idle
				}
			case cmd @ Groom(fromLocator, toLocator) =>
				val fromLoc = slots(fromLocator)
				val toLoc = slots(toLocator)
				ctx.signal(shuttle, Shuttle.GoTo(fromLoc))
				fetching(cmd)(
					{
						ctx.signal(shuttle, Shuttle.GoTo(toLoc))
						delivering(cmd)({
								ctx.reply(CompletedCommand(cmd))
								idle
						},
							errorLoaded
						)
					},
					idle
				)
			case cmd@LoopBack(fromChName, toChName) =>
				val fromChEp = inboundChannels(fromChName)
				val fromChLoc = inboundSlots(fromChName)
				val toChEp = outboundChannels(toChName)
				val toChLoc = outboundSlots(toChName)
				ctx.signal(shuttle, Shuttle.GoTo(fromChLoc))
				fetching(cmd)({
					ctx.signal(shuttle, Shuttle.GoTo(toChLoc))
					delivering(cmd)(
						{
							if (toChLoc.retrieve.exists(outboundChannels(toChName).send(_))) {
								ctx.reply(CompletedCommand(cmd))
								idle
							} else waitingForSlot(outboundChannels(toChName), toChLoc)(idle)
						},
						waitingForSlot(toChEp, toChLoc)(idle)
					)
				},
					waitingForLoad(fromChEp, fromChLoc) {
						ctx.signal(shuttle, Shuttle.GoTo(toChLoc));
						delivering(cmd)(
							{
								ctx.reply(ShuttleLevel.CompletedCommand(cmd))
								idle
							},
							{
								errorLoaded
							}
						)
					}
				)
		})(ctx)
	}

	private def errorLoaded: Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case cmd @ OutputFromTray(toChannelName) =>
				val outLoc = outboundSlots(toChannelName)
				val outChStart = outboundChannels(toChannelName)
				ctx.signal(shuttle, Shuttle.GoTo(outLoc))
				delivering(cmd)(
					{
						if(outLoc.retrieve.exists(outChStart.send(_))) idle
						else waitingForSlot(outboundChannels(toChannelName), outboundSlots(toChannelName))(idle)
					},
					waitingForSlot(outboundChannels(toChannelName), outboundSlots(toChannelName))(idle)
				)
			case cmd @ PutawayFromTray(toLoc) => idle
				delivering(cmd)(
					{
						ctx.reply(CompletedCommand(cmd))
						idle
					},
					errorLoaded
				)
		}
	}

	private def fetching(cmd: ShuttleLevel.ExternalCommand)(success: => Processor.DomainRun[ShuttleLevelMessage], failure: => Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) if !destination.isEmpty =>
				ctx.signal(shuttle, Shuttle.Load(destination))
				loading(success)(masterContext)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedEmpty(cmd, s"Did not get to desination. Instead got: $other"))
				failure
		}
	}

	private def loading(continue: => Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case Shuttle.Loaded(Shuttle.Load(loc)) =>
				continue
		}
	}

	private def delivering(cmd: ShuttleLevel.ExternalCommand)(success: => Processor.DomainRun[ShuttleLevelMessage], failure: => Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: SignallingContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) if destination.isEmpty =>
				ctx.signal(shuttle, Shuttle.Unload(destination))
				unloading(success)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedFull(cmd, s"Could not unload tray"))
				failure
		}
	}

	private def unloading(continue: => Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case Shuttle.Unloaded(Shuttle.Unload(loc), load) =>
				continue
		}
	}

	private def waitingForLoad(from: Channel.End[MaterialLoad, ShuttleLevelMessage], fromLoc: => ShuttleLocation)(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case tr :Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
				if(fromLoc isEmpty) {
					fromLoc store tr.load
					from.get(tr.load)
					ctx.signal(shuttle, Shuttle.Load(fromLoc))
					loading(continue)
				} else {
					throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
				}
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
		}
	}

	private def waitingForSlot(to: Channel.Start[MaterialLoad, ShuttleLevelMessage], toLoc: ShuttleLocation)(continue: => Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.SignallingContext[ShuttleLevelMessage] => {
			case tr: Channel.AcknowledgeLoad[MaterialLoad] if tr.channel == to.channelName =>
				ctx.signal(shuttle, Shuttle.Unload(toLoc))
				unloading{
					toLoc.retrieve.map(to.send(_))
					continue
				}
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for Shuttle to arrive to delivery location")
		}
	}

}
