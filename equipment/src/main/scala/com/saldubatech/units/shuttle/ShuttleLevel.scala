/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.{Identification, Types}
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Processor.{CommandContext, ProcessorRef}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel
import com.saldubatech.physics.Travel.{Distance, Speed}
import com.saldubatech.transport
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.shuttle.Shuttle.{LevelLocator, OnLeft, OnRight}

import scala.collection.mutable

object ShuttleLevel {


	sealed trait ShuttleLocation {
		lazy val contents: Option[MaterialLoad] = None
	}
	class FullLocation(load: MaterialLoad) extends ShuttleLocation {
		override lazy val contents: Option[MaterialLoad] = Some(load)
	}
	class EmptyLocation extends ShuttleLocation


	private def inboundLocator(idx: Int): LevelLocator = OnLeft(idx)
	private def outboundLocator(idx: Int): LevelLocator = OnRight(idx)

	sealed trait ShuttleLevelMessage extends Identification

	abstract class ShuttleCommand extends Identification.Impl() with ShuttleLevelMessage
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
	case class Groom(from: LevelLocator, to: LevelLocator) extends ExternalCommand

	sealed trait ShuttleNotification extends ShuttleLevelMessage
	sealed trait ExternalNotification extends ShuttleNotification
	case class InternalFailure(sig: ShuttleLevelMessage, reasong: String) extends Identification.Impl() with ExternalNotification
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

	sealed trait InternalNotification extends ShuttleNotification
	case class ArriveToDestination(loc: LevelLocator, mission: Mission) extends Identification.Impl() with InternalNotification
	case class PickupComplete(mission: Mission) extends Identification.Impl() with InternalNotification
	case class DropoffComplete(mission: Mission) extends Identification.Impl() with InternalNotification

	sealed trait MaterialMovement extends ShuttleLevelMessage
	case class AcknowledgeLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
		extends Channel.AckLoadImpl[MaterialLoad](channel, load, resource)
		with MaterialMovement
	case class TransferLoad(override val channel: String, override val load: MaterialLoad, override val resource: String)
			extends Channel.TransferLoadImpl[MaterialLoad](channel, load, resource)
			with MaterialMovement
	case class ConsumeLoad(override val channel: String)
		extends Channel.LoadConsumedImpl[MaterialLoad](channel)
			with MaterialMovement

/*

	private def receiving(cmd: ExternalCommand, inbound: Option[Channel.End[MaterialLoad, ShuttleProfile]])
	                     (implicit ctx: CommandContext[ShuttleProfile]): Boolean =
		if(inbound nonEmpty) {
			inbound.foreach(chEnd => ctx.tellSelf(Receive(chEnd, Mission(ctx.from, cmd, ctx.now))))
			true
		} else {
			ctx.reply(FailedCommand(cmd, s"Unknown Inbound Channel"))
			false
		}

	def idle(shuttle: Shuttle, inbound: mutable.Map[String, (ShuttleLocation, Channel.End[MaterialLoad, ShuttleProfile])],
	         outbound: mutable.Map[String, (ShuttleLocation, Channel.Start[MaterialLoad, ShuttleProfile])]) : Processor.DomainRun[ShuttleProfile]
	= new Processor.DomainRun[ShuttleProfile] {
			override def process(processMessage: ShuttleProfile)(implicit ctx: CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = processMessage match {
				case cmd @ Store(fromChannel, toLoc) => storing(cmd)
				case cmd @ Retrieve(fromLoc, toChannel) => retrieving(cmd)
				case cmd @ Groom(fromLoc, toLoc) => grooming(cmd)
				case cmd @ LoopBack(fromChannel, toChannel) =>
					// Send shuttle to pickup destination and transfer to "loopingback"
					if (receiving(cmd, inbound.get(fromChannel).map(_._2))) loopingBack(shuttle, inbound.values.map(t => t._2 -> t._1).toMap) else this
				case loadArrival @ TransferLoad(channel, load, resource) =>
					// if destination is empty, extract load and acknowledge
					if(inbound.get(channel).exists(t => t._1.contents.isEmpty)) {
						val chEnd = inbound(channel)._2
						chEnd.releaseLoad(load)
						inbound += channel -> (FullLocation(load), chEnd)
					}
					idle(inbound, outbound) // or this
				case channelCleared @ AcknowledgeLoad(channel, load, resource) =>
					// if origin is busy send the load & empty location
					for {
						(loc, chStart) <- outbound.get(channel)
						load <- loc.contents
					} {
						chStart.send(load)
						outbound += channel -> (EmptyLocation, chStart)
					}
					idle(inbound, outbound) // or this
				case otherExternalCommand: ExternalCommand => ctx.reply(FailedCommand(otherExternalCommand, "Unexpected Command While Idle"));this
				case other => ctx.reply(InternalFailure(other, "Unexpected Signal while Idle")); this
			}
	}

	def loopingBack(shuttle: Shuttle, inbound: Map[Channel.End[MaterialLoad, ShuttleProfile], ShuttleLocation]) : Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile] {
		override def process(processMessage: ShuttleProfile)(implicit ctx: CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = processMessage  match {
			case rcv @ Receive(inboundChannelEP, mission) =>
				val inboundLoc = inbound.get(inboundChannelEP)
				if(inboundLoc.nonEmpty && inboundLoc.head.contents.nonEmpty) {
					shuttle.load(inboundLoc.head.contents.head)
					ctx.tellSelf(PickupComplete(mission))
				}
				this

			case arr @ TransferLoad(channel, load) =>
			case ack @ AcknowledgeLoad(channel, load, resource) =>
			case other: ExternalCommand => ctx.reply(UnacceptedCmd(other))
		}

			if(inboundLoc.contents.nonEmpty) {
				inbound += inboundChannel -> (EmptyLocation, chEnd)
				chEnd.releaseLoad(inboundLoc.contents.head)
				processMessage match {
					case cmd @ LoopBack()
				}
			} else {
				processMessage match {
					case ...
				}
			}
	}
//	case class ShuttleTask(cmd: ExternalCommand, ctx: CommandContext[ShuttleProfile], channelResource: Option[String] = None)
*/
}

import ShuttleLevel._
class ShuttleLevel[ConnectProfile0, ConnectProfile1](name: String, depth: Int, initialInventory: Map[LevelLocator, MaterialLoad], shuttleProcessor: Processor[Shuttle.ShuttleSignal], initialPosition: LevelLocator = OnRight(0))
                                                    (implicit clockRef: ActorRef[ClockMessage], controllerRef: ActorRef[ControllerMessage]) extends Identification.Impl(name) {
	private val slots: mutable.Map[LevelLocator, ShuttleLocation] = mutable.Map.empty ++ (0 until depth).flatMap(idx => Seq(OnRight(idx) -> new EmptyLocation(), OnLeft(idx) -> new EmptyLocation)).toMap
	initialInventory.foreach(e => slots.put(e._1, new FullLocation(e._2)))

	private var shuttle = _
	private val configurer = new Processor.DomainConfigure[ShuttleLevelMessage] {
		override def configure(config: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = config match {
			case ShuttleLevel.NoConfigure =>
				shuttle = ctx.aCtx.spawn(shuttleProcessor.init, shuttleProcessor.processorName)
				ctx.tellTo(shuttle, Shuttle.Configure(initialPosition))
				idle
		}
	}

	private val idle = new Processor.DomainRun[ShuttleLevelMessage] {
		override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
			case cmd@Store(from, to) =>
				ctx.tellTo(shuttle, Shuttle.GoTo(fromLoc))
				fetching(fromLoc, to)(deliver(to)(release(to), idleLoaded), waitForLoad(deliver(release(to), idleLoaded)))
		}
	}

	private def fetching(from: LevelLocator, to: LevelLocator)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage]) = new Processor.DomainRun[ShuttleLevelMessage] {
		override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
			case Shuttle.Arrived(Shuttle.GoTo(fromLoc)) =>
				if (slots.get(fromLoc).exists(slot => slot.contents.nonEmpty)) {
					ctx.tellTo(shuttle, Shuttle.Load(fromLoc, slots(fromLoc).contents.head))
					success
				} else {
					failure
				}
		}
	}

	private def deliver(to: LevelLocator)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage]) =
		new Processor.DomainRun[ShuttleLevelMessage] {
			override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				case Shuttle.Loaded(Shuttle.Load(loc, load)) =>
					if (slots.get(to).exists(slot => slot.contents.isEmpty)) {
						ctx.tellTo(shuttle, Shuttle.GoTo(to))
						success
					}
					else failure
			}
		}

	private def release(to: LevelLocator) =
		new Processor.DomainRun[ShuttleLevelMessage] {
			override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				case Shuttle.Arrived(loc) => //Shuttle.Unload(loc, loadRef)) =>
					slots.put(to, new FullLocation(loadRef.inspect.head))
					idle
			}
		}

	private def release(to: LevelLocator) =
		new Processor.DomainRun[ShuttleLevelMessage] {
			override def process(processMessage: ShuttleLevelMessage)(implicit ctx: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
				case Offload(to) =>
					ctx.tellTo(shuttle, )
			}
		}

	val processor = new Processor[ShuttleLevelMessage](name, clockRef, controllerRef, configurer)


}
