/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, Delay}
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.ProcessorRef
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.physics.Travel
import com.saldubatech.physics.Travel.Speed
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.units.shuttle.ShuttleLevel.ShuttleLevelMessage
import com.saldubatech.util.LogEnabled

object Shuttle {
	object ShuttleTravel {
		def apply(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, acquireTime: Delay, releaseTime: Delay) =
			new ShuttleTravel(distancePerTick, rampUpLength,rampDownLength, acquireTime, releaseTime)
	}

	class ShuttleTravel(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, val acquireTime: Delay, val releaseTime: Delay) extends Travel(distancePerTick, rampUpLength, rampDownLength) {
		def travelTime(from: Int, to: Int): Delay = travelTime(from -to)
		def timeToPickup(from: LevelLocator, to: LevelLocator): Delay = acquireTime + travelTime(from.idx, to.idx)
		def timeToDeliver(from: LevelLocator, to: LevelLocator): Delay = releaseTime + travelTime(from.idx, to.idx)
	}

	sealed trait LevelLocator{val idx: Int}
	case class OnRight(override val idx: Int) extends LevelLocator
	case class OnLeft(override val idx: Int) extends LevelLocator

	case class ShuttleLocation(at: LevelLocator) {
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
	sealed trait ShuttleSignal

	sealed trait ShuttleConfigure extends ShuttleSignal
	case class Configure(initialPosition: ShuttleLocation) extends Identification.Impl() with ShuttleConfigure


	sealed trait ShuttleCommand extends ShuttleSignal
	case class Load(loc: ShuttleLocation) extends Identification.Impl() with ShuttleCommand
	case class Unload(loc: ShuttleLocation) extends Identification.Impl() with ShuttleCommand
	case class GoTo(loc: ShuttleLocation) extends Identification.Impl() with ShuttleCommand

	trait ShuttleNotification extends ShuttleLevelMessage
	case class CompleteConfiguration(pr: Processor.ProcessorRef) extends Processor.BaseCompleteConfiguration(pr) with ShuttleNotification
	case class UnacceptableCommand(cmd: ShuttleCommand, reason: String) extends Identification.Impl() with ShuttleNotification
	case class Loaded(cmd: Load) extends Identification.Impl() with ShuttleNotification
	case class Unloaded(cmd: Unload, load: MaterialLoad) extends Identification.Impl() with ShuttleNotification
	case class Arrived(cmd: GoTo) extends Identification.Impl() with ShuttleNotification

	protected sealed trait InternalSignal extends ShuttleSignal
	case class Arriving(cmd: GoTo, toLocation: ShuttleLocation) extends Identification.Impl() with InternalSignal
	case class DoneLoading(cmd: Load) extends Identification.Impl() with InternalSignal
	case class DoneUnloading(cmd: Unload) extends Identification.Impl() with InternalSignal

	def buildProcessor(name: String, travelPhysics: ShuttleTravel, clock: ActorRef[ClockMessage], controller: ActorRef[ControllerMessage]): Processor[ShuttleSignal] =
		new Processor(name, clock, controller, new Shuttle(name, travelPhysics).configurer)
}

class Shuttle(name: String, travelPhysics: Shuttle.ShuttleTravel) extends Identification.Impl(name) with LogEnabled {
	import Shuttle._

	private var tray: Option[MaterialLoad] = None
	private var currentLocation: ShuttleLocation = _

	private var currentClient: Option[ProcessorRef] = None

	def configurer: Processor.DomainConfigure[ShuttleSignal] = new Processor.DomainConfigure[ShuttleSignal] {
		override def configure(config: ShuttleSignal)(implicit ctx: Processor.SignallingContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = config match {
			case Configure(loc) =>
				currentLocation = loc
				ctx.configureContext.reply(Shuttle.CompleteConfiguration(ctx.aCtx.self) )
				ctx.aCtx.log.debug(s"Completed configuration and notifiying ${ctx.from}")
				idleEmpty
			case other => throw new IllegalArgumentException(s"Unknown Signal; $other")
		}
	}

	def idleFull: Processor.DomainRun[ShuttleSignal] = {
		implicit ctx: Processor.SignallingContext[ShuttleSignal] => {
			//		new Processor.DomainRun[ShuttleSignal] {
			//		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
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
			case other: ShuttleCommand =>
				println(s"Responding with Unacceptable Command: $other to ${ctx.from}")
				ctx.reply(UnacceptableCommand(other, s"Command not applicable when Tray loaded with $tray at $currentLocation"))
				idleFull
		}
	}


	def idleEmpty: Processor.DomainRun[ShuttleSignal] = {
		implicit ctx: Processor.SignallingContext[ShuttleSignal] => {
			//		new Processor.DomainRun[ShuttleSignal] {
			//		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
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
			case other: ShuttleCommand =>
				ctx.reply(UnacceptableCommand(other, s"Command not applicable while at place"))
				idleEmpty
		}
	}


	private def loading: Processor.DomainRun[ShuttleSignal] = {
		implicit ctx: Processor.SignallingContext[ShuttleSignal] => {
			//		new Processor.DomainRun[ShuttleSignal] {
			//		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
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

	private def unloading: Processor.DomainRun[ShuttleSignal] = {
		implicit ctx: Processor.SignallingContext[ShuttleSignal] => {
			//		new Processor.DomainRun[ShuttleSignal] {
			//		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
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


	private def running: Processor.DomainRun[ShuttleSignal] = {
		implicit ctx: Processor.SignallingContext[ShuttleSignal] => {
			//		new Processor.DomainRun[ShuttleSignal] {
			//		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
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
