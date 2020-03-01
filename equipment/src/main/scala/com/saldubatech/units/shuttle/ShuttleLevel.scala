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
	case class FullLocation(load: MaterialLoad) extends ShuttleLocation {
		override lazy val contents: Option[MaterialLoad] = Some(load)
	}
	case object EmptyLocation extends ShuttleLocation


	private def inboundLocator(idx: Int): LevelLocator = OnLeft(idx)
	private def outboundLocator(idx: Int): LevelLocator = OnRight(idx)

	sealed trait ShuttleProfile extends Identification

	abstract class ShuttleCommand extends Identification.Impl() with ShuttleProfile
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

	sealed trait ShuttleNotification extends ShuttleProfile
	sealed trait ExternalNotification extends ShuttleNotification
	case class InternalFailure(sig: ShuttleProfile, reasong: String) extends Identification.Impl() with ExternalNotification
	case class FailedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class NotAcceptedCommand(cmd: ExternalCommand, reason: String) extends Identification.Impl() with ExternalNotification
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with ExternalNotification
	case class LoadArrival(fromCh: String) extends Identification.Impl() with ExternalNotification


	case class Mission(source: Processor.ProcessorRef, cmd: ExternalCommand, at: Clock.Tick)
	sealed trait InternalCommand extends ShuttleCommand
	case class Fetch(from: LevelLocator, mission: Mission) extends InternalCommand
	case class Drop(to: LevelLocator, mission: Mission) extends InternalCommand
	case class Deliver(ch: Channel.Start[MaterialLoad, ShuttleProfile], onSlot: String, mission: Mission) extends InternalCommand
	case class Receive(ch: Channel.End[MaterialLoad, ShuttleProfile], mission: Mission) extends InternalCommand

	sealed trait InternalNotification extends ShuttleNotification
	case class ArriveToDestination(loc: LevelLocator, mission: Mission) extends Identification.Impl() with InternalNotification
	case class PickupComplete(mission: Mission) extends Identification.Impl() with InternalNotification
	case class DropoffComplete(mission: Mission) extends Identification.Impl() with InternalNotification

	sealed trait MaterialMovement extends ShuttleProfile
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

}

import ShuttelLevel._
class ShuttleLevel[ConnectProfile0, ConnectProfile1](
	                                                    name: String, depth: Int, initialInventory: Map[LevelLocator, MaterialLoad], travelPhysics: ShuttleTravel, initialPosition: LevelLocator = OnRight(0))
                                                    (implicit clockRef: ActorRef[ClockMessage], controllerRef: ActorRef[ControllerMessage]) extends Identification.Impl(name) {
	import ShuttelLevel._

	// Elements to be initialized during Configuration ======================================
	private val shuttleController: ProcessorRef = null
	//=======================================================================================


	// Storage numbered as:
	// -1, 0 => Exchange locations with channels (Left <-> inbound), (Right <-> outbound)
	// 0...depth-1 storage loactions
	private val storage = mutable.Map.empty[LevelLocator, ShuttleLocation] ++=
		(-1 until depth).map(OnRight(_) -> EmptyLocation).toMap ++=
		(-1 until depth).map(OnLeft(_) -> EmptyLocation).toMap ++=
		initialInventory.map(e => e._1 -> FullLocation(e._2))

	// Inbound-Outbound interfacing
	private val outbound: mutable.Map[String, (Int, Channel.Start[MaterialLoad, ShuttleProfile])] = mutable.Map.empty
	private val inbound: mutable.Map[String, (Int, Channel.End[MaterialLoad, ShuttleProfile])] = mutable.Map.empty

	private def configureInbound(channelName: String, index: Int, end: Channel.End[MaterialLoad, ShuttleProfile]) = {
		end.register(shuttleProcessor)

	}

	/**
		* Compile the processing logic for a
		* @param next the processing that needs to be put in place after the signal is processed
		* @return
		*/
	def inboundProcessing(next: Processor.DomainRun[ShuttleProfile])(implicit ctx: CommandContext[ShuttleProfile]):
	PartialFunction[ShuttleProfile, Either[String,Processor.DomainRun[ShuttleProfile]]] =
		inbound.values.map(_._2.receiveLoad2).reduce((pf1, pf2) => pf1 orElse pf2).andThen{_ => Right(next)}


	/**
		* The "Sender" endpoint of an outbound channel
		* @param locIdx: The location that interfaces with the external channel
		*/
	class LoadSender(locIdx: LevelLocator) extends Channel.Source[MaterialLoad, ShuttleProfile] {
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): Signal = AcknowledgeLoad(channel, load, resource)

		override def loadAcknowledge(load: MaterialLoad)(implicit ctx: CommandContext[ShuttleProfile]): Option[MaterialLoad] = {
			// if load in location staged -> send it and empty location
			storage.get(locIdx) match {
				case Some(FullLocation(load)) =>
					if(inbound(Math.abs(locIdx.at)).send(load)) storage += locIdx -> EmptyLocation
				case other => ()
			}
			// always --> If command pending, try to complete it (should be a retrieve or loopback command)
		}
	}

	/**
		* The Receiving endpoint for inbound channels
		* @param locIdx: The location that interfaces with the external channel
		*/
	class LoadReceiver(locIdx: LevelLocator) extends Channel.Sink[MaterialLoad, ShuttleProfile] {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
			TransferLoad(channel, load, resource)
		override def releaseBuilder(channel: String): ConsumeSignal = ConsumeLoad(channel)
		//noinspection MapGetOrElseBoolean
		override def loadArrived(endpoint: Channel.End[MaterialLoad, ShuttleProfile], load: MaterialLoad)(implicit ctx: CommandContext[ShuttleProfile]): Boolean = {
			storage.get(locIdx).map {
				lc => {
					ctx.tellTo(shuttleController)(LoadArrival(endpoint.channelName))
					if (lc.contents isEmpty) {
						storage += locIdx -> FullLocation(load)
						endpoint.releaseLoad(load)
						true
					} else false
				}
			}.getOrElse(false)
		}
	}

	/* State Tracking */

	// Load physically being transported in the
	private var wip: Option[MaterialLoad] = None
	private var currentPosition: LevelLocator = initialPosition

	/**
		*
		*/
	private lazy val idle: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile] {
		override def process(processMessage: ShuttleProfile)(implicit ctx: CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = processMessage match {
			case ext: ExternalCommand =>
				val tryResult = (inboundProcessing(this) orElse[ShuttleProfile, Either[String,Processor.DomainRun[ShuttleProfile]]] {
					case cmd@Store(fromCh, toLoc) =>
						for {
							chEp <- inbound.get(fromCh).map(Right(_)).getOrElse(Left(s"Channel $fromCh is not Available"))
							from <- storage.get(OnRight(chEp._1)).map(Right(_)).getOrElse(Left(s"Location at ${chEp._1} is not available"))
							to <- storage.get(toLoc).map(Right(_)).getOrElse(Left(s"Location at $toLoc is not available"))
						} yield {
							(from.contents, to.contents) match {
								case (_, Some(_)) =>
									// Reject command, Destination is not empty
									ctx.reply(NotAcceptedCommand(cmd, s"Destination $toLoc is not available"))
									this
								case (Some(_), None) =>
									// Ready, execute command
									val fetchTime = travelPhysics.timeToPickup(currentPosition, OnRight(chEp._1))
									ctx.tellSelf(Receive(chEp._2, Mission(ctx.from, cmd, ctx.now)), Some(fetchTime))
									receiving
								case (None, None) =>
									// Need to wait for load to arrive
									waitForLoadArrival += OnRight(chEp._1) -> cmd
									waitingForLoad
							}
						}
				}) (processMessage)
				tryResult.fold(reason => {ctx.reply(NotAcceptedCommand(ext, reason));this}, dr => dr)
			case other =>
				ctx.aCtx.log.error(s"Unexpected Command: $other")
				throw new IllegalStateException(s"Cannot process $other when in Idle state")
				this
		}
	}
*/

	/*
	private val readyCommands: mutable.Queue[ShuttleTask] = mutable.Queue.empty
	private val waitForLoadArrival: mutable.Map[LevelLocator,InboundCommand] = mutable.Map.empty


	private lazy val receiving: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile] {
		override def process(processMessage: ShuttleProfile)(implicit ctx: Processor.CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = {
			case Receive(ch, Mission(source, Store(fromCh, toLoc), at)) =>
				for {
					locIdx <- inbound.get(fromCh).map(t => OnLeft(t._1))
					fromLoc <- storage.get(locIdx)
				} yield {
					fromLoc match {
						case FullLocation(load) =>
							wip = Some(load)
							storage += locIdx -> EmptyLocation
							ch.releaseLoad(load)
							currentPosition = locIdx
							ctx.tellSelf(Drop(toLoc, mission), Some(travelPhysics.timeToDeliver(currentPosition, toLoc)))
					}
					droppingOff
				}
		}
	}

	private lazy val pickingUp: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile]{
		override def process(processMessage: ShuttleProfile)(implicit ctx: Processor.CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = {
			case PickupComplete(mission) =>
				mission match {
					case Mission(to, Store(fromCh, toLoc), at) =>
						ctx.tellSelf(Drop(toLoc, mission), Some(travelPhysics.timeToDeliver(currentPosition, toLoc)))
					case Mission(to, Retrieve(fromLoc,toCh), at) =>
						for{
							ch <- outbound.get(toCh)
						} ctx.tellSelf(Drop(OnLeft(ch._1), mission),Some(travelPhysics.timeToDeliver(currentPosition, OnLeft(ch._1))))
					case Mission(to, Groom(fromLoc, toLoc), at) =>
					case Mission(to, LoopBack(fromCh, toCh), at) =>
				}
		}
	}
	private lazy val droppingOff: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile]{
		override def process(processMessage: ShuttleProfile)(implicit ctx: Processor.CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = {
			case DropoffComplete(mission) =>
				mission match {
					case Mission(to, Store(fromCh, toLoc), at) => ctx.tellTo(to)(CompletedCommand(mission.cmd))
					case Mission(to, Retrieve(fromLoc, toCh), at) => ctx.tellTo(to)(CompletedCommand(mission.cmd))
				}
		}
	}
	private lazy val busyExecuting: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile]{
		override def process(processMessage: ShuttleProfile)(implicit ctx: Processor.CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = {
			val tryResult: Either[String, Processor.DomainRun[ShuttleProfile]] = (inboundProcessing(this) orElse[ShuttleProfile, Either[String, Processor.DomainRun[ShuttleProfile]]] {
				case MissionComplete(cmd) => Right(idle)
				case cmd: ExternalCommand =>
					ctx.reply(NotAcceptedCommand(cmd, s"Shuttle $name is busy"))
					Right(this)
			})(processMessage)
			tryResult.fold(reason => throw new UnknownError(s"An Unknown Error has occurred: $reason"), dr => dr)
		}
	}

	private lazy val waitingForLoad: Processor.DomainRun[ShuttleProfile] = new Processor.DomainRun[ShuttleProfile] {
		override def process(processMessage: ShuttleProfile)(implicit ctx: CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] =
			inboundProcessing(busyExecuting)(processMessage).fold(reason => throw new UnknownError(s"An Unknown Error has occurred: $reason"), dr => dr)
	}

	private lazy val shuttleRunner: Processor.DomainRun[ShuttleProfile] =
		new Processor.DomainRun[ShuttleProfile] {


			override def process(processMessage: ShuttleProfile)(implicit ctx: Processor.CommandContext[ShuttleProfile]): Processor.DomainRun[ShuttleProfile] = {
				val processor = inboundProcessing(this) orElse internalProcess orElse[ShuttleProfile, Option[MaterialLoad]] {
					case Store(fromCh, toLoc) =>
						for{
							chEp <- inbound.get(fromCh)
							to <- storage.get(toLoc)
							from <- storage.get(OnRight(chEp._1))
							cts <- from.contents
						} yield {
							storage.remove(toLoc)
							reserved += toLoc -> to
							if(from.contents.nonEmpty) {
								storage.remove(OnRight(chEp._1))
								reserved += OnRight(chEp._1) -> from
								val fetchTime = travelPhysics.timeToPickup(currentPosition, OnRight(chEp._1))
								ctx.tellSelf(Receive(chEp._2), Some(fetchTime))
								ctx.tellSelf(Drop(toLoc), Some(fetchTime + travelPhysics.timeToDeliver(OnRight(chEp._1), toLoc)))
							} else {
								pendingCommands += chEp._1 -> Store(fromCh, toLoc)
							}
							cts
						}
					case Retrieve(fromLoc, toCh) =>
						for {
							chEp <- outbound.get(toCh)
							fromGuard <- storage.get(fromLoc)
							slot <- chEp._2.reserveSlot
							from <- storage.remove(fromLoc)
							content <- from.contents
						} yield (from, slot) match {
							case (FullLocation(load), to) =>
								reserved += fromLoc -> from
								val fetchTime = travelPhysics.timeToPickup(currentPosition, fromLoc)
								ctx.tellSelf(Fetch(fromLoc), Some(fetchTime))
								ctx.tellSelf(Deliver(chEp._2, to), Some(fetchTime+travelPhysics.timeToDeliver(fromLoc, OnRight(chEp._1))))
								load
							case other => content
						}
					case LoopBack(fromCh, toCh) => None
					case Groom(fromLoc, toLoc) =>
						for {
							fromGuard <- storage.get(fromLoc)
							to <- storage.remove(toLoc)
							from <- storage.remove(fromLoc)
							content <- from.contents
						} yield (from, to) match {
							case (FullLocation(load), EmptyLocation) =>
								reserved += fromLoc -> from
								reserved += toLoc -> to
								// Can schedule both here if we assume there are no internal failures. Otherwise, we need to provide "continuations" in the internal messages themselves.
								ctx.tellSelf(Fetch(fromLoc), Some(travelPhysics.timeToPickup(currentPosition, fromLoc)))
								ctx.tellSelf(Drop(toLoc), Some(travelPhysics.timeToPickup(currentPosition, fromLoc) + travelPhysics.timeToDeliver(fromLoc, toLoc)))
								load
							case other => content
						}
				}
				processor.apply(processMessage)
				this
			}

			private def internalProcess(implicit ctx: Processor.CommandContext[ShuttleProfile]): PartialFunction[ShuttleProfile, Option[MaterialLoad]] = {
				case Fetch(from) =>
					reserved.remove(from).flatMap{ld =>
						currentPosition = from
						wip = ld.contents
						wip
					}
				case Drop(to) =>
					reserved.remove(to).flatMap {ld =>
						currentPosition = to
						val currentWip = wip
						wip.foreach{ld => storage += to -> FullLocation(ld)}
						wip = None
						currentWip
					}
				case Deliver(ch, onSlot) =>
					val currentWip = wip
					currentWip.map(ch.send(_, onSlot))
					wip = None
					currentWip
			}
		}

	val shuttleConfigurer: Processor.DomainConfigure[ShuttleProfile] = null

	lazy val processor = new Processor(name, clockRef, controllerRef, shuttleConfigurer)


	object shuttleSenderProfile extends Channel.Source[MaterialLoad, ShuttleProfile] {
		override def acknowledgeBuilder(ch: String, ld: MaterialLoad, rs: String): Signal = new Channel.AckLoadImpl[MaterialLoad](ch, ld, rs) with ShuttleProfile
		override def loadAcknowledge(load: MaterialLoad): Option[MaterialLoad] = Some(load)
	}

	object shuttleReceiverProfile extends Channel.Sink[MaterialLoad, ShuttleProfile]{
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ShuttleProfile

		override def releaseBuilder(channel: String): ReleaseSignal = new Channel.DqNextLoadImpl[MaterialLoad](channel) with ShuttleProfile

		override def loadArrived(load: MaterialLoad): Option[MaterialLoad] = ???
	}
*/
}
