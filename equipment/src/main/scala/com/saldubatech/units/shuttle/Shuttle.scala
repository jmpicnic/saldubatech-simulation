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
import com.saldubatech.util.LogEnabled

object Shuttle {
	class Ref[C] {
		private var content: Option[C] = None
		def assign(c: C) = content = Some(c)
		def inspect: Option[C] = content
		def delete: Unit = content = None
	}
	object ShuttleTravel {
		def apply(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, acquireTime: Delay, releaseTime: Delay) =
			new ShuttleTravel(distancePerTick, rampUpLength,rampDownLength, acquireTime, releaseTime)
	}

	class ShuttleTravel(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, val acquireTime: Delay, val releaseTime: Delay) extends Travel(distancePerTick, rampUpLength, rampDownLength) {
		def travelTime(from: Int, to: Int): Delay = travelTime(from -to)
		def timeToPickup(from: LevelLocator, to: LevelLocator): Delay = acquireTime + travelTime(from.at, to.at)
		def timeToDeliver(from: LevelLocator, to: LevelLocator): Delay = releaseTime + travelTime(from.at, to.at)
	}

	sealed trait LevelLocator{val at: Int}
	case class OnRight(override val at: Int) extends LevelLocator
	case class OnLeft(override val at: Int) extends LevelLocator

	sealed trait ShuttleSignal

	sealed trait ShuttleConfigure extends ShuttleSignal
	object NoConfigure extends Identification.Impl() with ShuttleConfigure


	sealed trait ShuttleCommand extends ShuttleSignal
	case class Load(loc: LevelLocator, load: MaterialLoad) extends Identification.Impl() with ShuttleCommand
	case class Unload(loc: LevelLocator, store: Ref[MaterialLoad]) extends Identification.Impl() with ShuttleCommand
	case class GoTo(loc: LevelLocator) extends Identification.Impl() with ShuttleCommand

	trait ShuttleNotification
	case class UnacceptableCommand(cmd: ShuttleCommand, reason: String) extends Identification.Impl() with ShuttleNotification
	case class Loaded(cmd: ShuttleCommand) extends Identification.Impl() with ShuttleNotification
	case class Unloaded(cmd: ShuttleCommand) extends Identification.Impl() with ShuttleNotification
	case class Arrived(cmd: ShuttleCommand) extends Identification.Impl() with ShuttleNotification

	protected sealed trait InternalSignal extends ShuttleSignal
	case class Arriving(cmd: ShuttleCommand, toLocation: LevelLocator) extends Identification.Impl() with InternalSignal
	case class DoneLoading(cmd: ShuttleCommand, load: MaterialLoad) extends Identification.Impl() with InternalSignal
	case class DoneUnloading(cmd: ShuttleCommand, store: Ref[MaterialLoad]) extends Identification.Impl() with InternalSignal

	def ShuttleProcessor(name: String, travelPhysics: ShuttleTravel, clock: ActorRef[ClockMessage], controller: ActorRef[ControllerMessage]): Processor[ShuttleSignal] =
		new Processor(name, clock, controller, new Shuttle(name, travelPhysics).configurer)
}

class Shuttle(name: String, travelPhysics: Shuttle.ShuttleTravel) extends Identification.Impl(name){
	import Shuttle._

	private var tray: Option[MaterialLoad] = None
	private var currentLocation: LevelLocator = OnRight(0)

	private var currentClient: Option[ProcessorRef] = None

	def configurer: Processor.DomainConfigure[ShuttleSignal] = new Processor.DomainConfigure[ShuttleSignal] {
		override def configure(config: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = config match {
			case cfg: ShuttleConfigure => idleEmpty
			case other => throw new IllegalArgumentException(s"Unknown Signal; $other")
		}
	}

	def idleFull: Processor.DomainRun[ShuttleSignal] = new Processor.DomainRun[ShuttleSignal] {
		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
			case cmd @ Unload(loc, store) =>
				if(loc == currentLocation) {
					ctx.tellSelf(DoneUnloading(cmd, store))
					unloading
					currentClient = Some(ctx.from)
				} else ctx.reply(UnacceptableCommand(cmd,
					s"Current Location $currentLocation incompatible with $loc"))
				this
			case cmd @ GoTo(loc) =>
				if(loc == currentLocation) {ctx.reply(Arrived(cmd)); this}
				else  {
					ctx.tellSelf(Arriving(cmd, loc), travelPhysics.travelTime(currentLocation.at, loc.at))
					currentClient = Some(ctx.from)
					running
				}
			case other: ShuttleCommand =>
				println(s"Responding with Unacceptable Command: $other to ${ctx.from}")
				ctx.reply(UnacceptableCommand(other,s"Command not applicable when Tray loaded with $tray at $currentLocation"))
				this
		}
	}

	def idleEmpty: Processor.DomainRun[ShuttleSignal] = new Processor.DomainRun[ShuttleSignal] {
		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
			case cmd @ Load(loc, load) =>
				if(currentLocation == loc) {
					println(s"Loading to be complete by now(${ctx.now}) + Acquire Time: ${travelPhysics.acquireTime}")
					ctx.tellSelf(DoneLoading(cmd, load), travelPhysics.acquireTime)
					currentClient = Some(ctx.from)
					loading
				}	else {
					ctx.reply(UnacceptableCommand(cmd, s"Current Location $currentLocation incompatible with $loc or Tray not empty $tray"))
					this
				}
			case cmd @ GoTo(loc) =>
				if(loc == currentLocation) {ctx.reply(Arrived(cmd)); this}
				else  {
					ctx.tellSelf(Arriving(cmd, loc), travelPhysics.travelTime(currentLocation.at, loc.at))
					currentClient = Some(ctx.from)
					running
				}
			case other: ShuttleCommand =>
				ctx.reply(UnacceptableCommand(other,s"Command not applicable while at place"))
				this
		}
	}

	private def loading: Processor.DomainRun[ShuttleSignal] = new Processor.DomainRun[ShuttleSignal] with LogEnabled {
		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
			case DoneLoading(cmd, load) =>
				tray = Some(load)
				println(s"Loaded Tray with $load at ${ctx.now}")
				ctx.tellTo(currentClient.head, Loaded(cmd))
				currentClient = None
				idleFull
			case other =>
				log.error(s"Unknown signal received $other")
				this
		}
	}

	private def unloading: Processor.DomainRun[ShuttleSignal] = new Processor.DomainRun[ShuttleSignal] with LogEnabled {
		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
			case DoneUnloading(cmd, store) =>
				tray.foreach(store.assign)
				tray = None
				ctx.tellTo(currentClient.head, Unloaded(cmd))
				currentClient = None
				idleEmpty
			case other =>
				log.error(s"Unknown signal received $other")
				this
		}
	}

	private def running: Processor.DomainRun[ShuttleSignal] = new Processor.DomainRun[ShuttleSignal] with LogEnabled {
		override def process(processMessage: ShuttleSignal)(implicit ctx: Processor.CommandContext[ShuttleSignal]): Processor.DomainRun[ShuttleSignal] = processMessage match {
			case Arriving(cmd, toLocation) =>
				currentLocation = toLocation
				ctx.tellTo(currentClient.head, Arrived(cmd))
				currentClient = None
				if(tray isEmpty) idleEmpty else idleFull
			case other =>
				log.error(s"Unknown signal received $other")
				this
		}
	}
}
