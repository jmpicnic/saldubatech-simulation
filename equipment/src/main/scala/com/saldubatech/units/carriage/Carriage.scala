/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.carriage

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.ProcessorRef
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel
import com.saldubatech.physics.Travel.Speed
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.util.LogEnabled

object Carriage {
	sealed trait SlotLocator{val idx: Int}
	case class OnRight(override val idx: Int) extends SlotLocator
	case class OnLeft(override val idx: Int) extends SlotLocator

	case class Slot(at: SlotLocator) {
		private var contents: Option[MaterialLoad] = None
		def isEmpty = contents.isEmpty
		def store(load: MaterialLoad): Option[MaterialLoad] = if(contents isEmpty) {
			contents = Some(load)
			contents
		} else None
		def inspect: Option[MaterialLoad] = contents
		def retrieve: Option[MaterialLoad] = {
			val r = contents
			contents = None
			r
		}
	}

	object CarriageTravel {
		def apply(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, acquireTime: Delay, releaseTime: Delay) =
			new CarriageTravel(distancePerTick, rampUpLength,rampDownLength, acquireTime, releaseTime)
	}

	class CarriageTravel(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, val acquireTime: Delay, val releaseTime: Delay, interSlotDistance: Int = 1) extends Travel(distancePerTick, rampUpLength, rampDownLength) {
		def travelTime(from: Int, to: Int): Delay = travelTime(interSlotDistance*from - interSlotDistance*to)
		def timeToPickup(from: SlotLocator, to: SlotLocator): Delay = acquireTime + travelTime(from.idx, to.idx)
		def timeToDeliver(from: SlotLocator, to: SlotLocator): Delay = releaseTime + travelTime(from.idx, to.idx)
	}

	sealed trait CarriageSignal

	sealed trait CarriageConfigure extends CarriageSignal
	case class Configure(initialPosition: Slot) extends Identification.Impl() with CarriageConfigure


	sealed trait CarriageCommand extends CarriageSignal
	case class Load(loc: Slot) extends Identification.Impl() with CarriageCommand
	case class Unload(loc: Slot) extends Identification.Impl() with CarriageCommand
	case class GoTo(loc: Slot) extends Identification.Impl() with CarriageCommand

	case class CompleteConfiguration(pr: Processor.ProcessorRef) extends Processor.BaseCompleteConfiguration(pr) with CarriageNotification
	case class UnacceptableCommand(cmd: CarriageCommand, reason: String) extends Identification.Impl() with CarriageNotification
	case class Loaded(cmd: Load) extends Identification.Impl() with CarriageNotification
	case class Unloaded(cmd: Unload, load: MaterialLoad) extends Identification.Impl() with CarriageNotification
	case class Arrived(cmd: GoTo) extends Identification.Impl() with CarriageNotification

	protected sealed trait InternalSignal extends CarriageSignal
	case class Arriving(cmd: GoTo, toLocation: Slot) extends Identification.Impl() with InternalSignal
	case class DoneLoading(cmd: Load) extends Identification.Impl() with InternalSignal
	case class DoneUnloading(cmd: Unload) extends Identification.Impl() with InternalSignal

	def buildProcessor(name: String, travelPhysics: CarriageTravel, clock: ActorRef[ClockMessage], controller: ActorRef[ControllerMessage]): Processor[CarriageSignal] =
		new Processor(name, clock, controller, new Carriage(name, travelPhysics).configurer)
}

class Carriage(name: String, travelPhysics: Carriage.CarriageTravel) extends Identification.Impl(name) with LogEnabled {
	import Carriage._

	private var tray: Option[MaterialLoad] = None
	private var currentLocation: Slot = _

	private var currentClient: Option[ProcessorRef] = None

	def configurer: Processor.DomainConfigure[CarriageSignal] = new Processor.DomainConfigure[CarriageSignal] {
		override def configure(config: CarriageSignal)(implicit ctx: Processor.SignallingContext[CarriageSignal]): Processor.DomainRun[CarriageSignal] = config match {
			case Configure(loc) =>
				currentLocation = loc
				ctx.configureContext.reply(Carriage.CompleteConfiguration(ctx.aCtx.self) )
				ctx.aCtx.log.debug(s"Completed configuration and notifiying ${ctx.from}")
				idleEmpty
			case other => throw new IllegalArgumentException(s"Unknown Signal; $other")
		}
	}

	def idleFull: Processor.DomainRun[CarriageSignal] = {
		implicit ctx: Processor.SignallingContext[CarriageSignal] => {
			case cmd@Unload(loc) =>
				if (loc == currentLocation) {
					ctx.signalSelf(DoneUnloading(cmd), travelPhysics.releaseTime)
					currentClient = Some(ctx.from)
					unloading
				} else {
					ctx.reply(UnacceptableCommand(cmd,
						s"Current Location $currentLocation incompatible with $loc"))
					idleFull
				}
			case cmd@GoTo(loc) =>
				if (loc == currentLocation) {
					ctx.reply(Arrived(cmd)); idleFull
				}
				else {
					ctx.signalSelf(Arriving(cmd, loc), travelPhysics.travelTime(currentLocation.at.idx, loc.at.idx))
					currentClient = Some(ctx.from)
					running
				}
			case other: CarriageCommand =>
				println(s"Responding with Unacceptable Command: $other to ${ctx.from}")
				ctx.reply(UnacceptableCommand(other, s"Command not applicable when Tray loaded with $tray at $currentLocation"))
				idleFull
		}
	}


	def idleEmpty: Processor.DomainRun[CarriageSignal] = {
		implicit ctx: Processor.SignallingContext[CarriageSignal] => {
			case cmd@Load(loc) =>
				if (currentLocation == loc) {
					println(s"Loading to be complete by now(${ctx.now}) + Acquire Time: ${travelPhysics.acquireTime}")
					ctx.signalSelf(DoneLoading(cmd), travelPhysics.acquireTime)
					currentClient = Some(ctx.from)
					println(s"SelfSent DoneLoading")
					loading
				} else {
					ctx.reply(UnacceptableCommand(cmd, s"Current Location $currentLocation incompatible with $loc or Tray not empty $tray"))
					idleEmpty
				}
			case cmd@GoTo(loc) =>
				if (loc == currentLocation) {
					ctx.reply(Arrived(cmd)); idleEmpty
				}
				else {
					ctx.signalSelf(Arriving(cmd, loc), travelPhysics.travelTime(currentLocation.at.idx, loc.at.idx))
					currentClient = Some(ctx.from)
					running
				}
			case other: CarriageCommand =>
				ctx.reply(UnacceptableCommand(other, s"Command not applicable while idleEmpty at place $currentLocation: $other"))
				idleEmpty
		}
	}


	private def loading: Processor.DomainRun[CarriageSignal] = {
		implicit ctx: Processor.SignallingContext[CarriageSignal] => {
			case DoneLoading(cmd@Load(loc)) =>
				if (tray isEmpty) {
					if (loc isEmpty) {
						ctx.signal(currentClient.head, UnacceptableCommand(cmd, s"Cannot Load from an empty location $loc"))
						currentClient = None
						idleEmpty
					} else {
						tray = loc.retrieve
						println(s"Loaded Tray with ${tray.head} at ${ctx.now}")
						ctx.signal(currentClient.head, Loaded(cmd))
						currentClient = None
						idleFull
					}
				} else {
					ctx.signal(currentClient.head, UnacceptableCommand(cmd, s"Cannot load from empty Loaction $loc"))
					currentClient = None
					idleEmpty
				}
			case other =>
				log.error(s"Unknown signal received loading $other")
				loading
		}
	}

	private def unloading: Processor.DomainRun[CarriageSignal] = {
		implicit ctx: Processor.SignallingContext[CarriageSignal] => {
			case DoneUnloading(cmd@Unload(loc)) =>
				if (tray nonEmpty) {
					if (loc isEmpty) {
						loc.store(tray.head)
						ctx.signal(currentClient.head, Unloaded(cmd, tray.head))
						tray = None
						currentClient = None
						idleEmpty
					} else {
						ctx.signal(currentClient.head, UnacceptableCommand(cmd, s"Cannot Unload tray into non empty Location $loc"))
						currentClient = None
						idleFull
					}
				}
				else {
					ctx.signal(currentClient.head, UnacceptableCommand(cmd, s"Cannot Unload an empty tray"))
					idleEmpty
				}
			case other =>
				log.error(s"Unknown signal received unloading: $other")
				unloading
		}
	}


	private def running: Processor.DomainRun[CarriageSignal] = {
		implicit ctx: Processor.SignallingContext[CarriageSignal] => {
			case Arriving(cmd, toLocation) =>
				currentLocation = toLocation
				ctx.signal(currentClient.head, Arrived(cmd))
				currentClient = None
				if (tray isEmpty) idleEmpty else idleFull
			case other =>
				log.error(s"Unknown signal received running: $other")
				running
		}
	}

}
