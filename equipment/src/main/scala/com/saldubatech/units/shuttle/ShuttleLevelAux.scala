/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.{CommandContext, ProcessorRef}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.shuttle.Shuttle.ShuttleLocation

object ShuttleLevelAux {
	import ShuttleLevel._

	def fetching(shuttle: ProcessorRef, cmd: ShuttleLevel.ExternalCommand)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) =>
				ctx.tellTo(shuttle, Shuttle.Load(destination))
				loading(success)(ctx)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedEmpty(cmd, s"Could not load tray"))
				failure
		}
	}

	def loading(continue: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Loaded(Shuttle.Load(loc)) =>
				continue
		}
	}

	def delivering(shuttle: ProcessorRef, cmd: ShuttleLevel.ExternalCommand)(success: Processor.DomainRun[ShuttleLevelMessage], failure: Processor.DomainRun[ShuttleLevelMessage])(implicit masterContext: CommandContext[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Arrived(Shuttle.GoTo(destination)) =>
				ctx.tellTo(shuttle, Shuttle.Unload(destination))
				unloading(success)
			case other: Shuttle.ShuttleNotification =>
				masterContext.reply(ShuttleLevel.FailedEmpty(cmd, s"Could not unload tray"))
				failure
		}
	}

	def unloading(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case Shuttle.Unloaded(Shuttle.Unload(loc), load) =>
				continue
		}
	}

	def waitingForLoad(shuttle: ProcessorRef, from: Channel.End[MaterialLoad, ShuttleLevelMessage], fromLoc: ShuttleLocation)(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case tr :Channel.TransferLoad[MaterialLoad] if tr.channel == from.channelName =>
				if(fromLoc isEmpty) {
					fromLoc store tr.load
					from.releaseLoad(tr.load)
					ctx.tellTo(shuttle, Shuttle.Load(fromLoc))
					loading(continue)
				} else {
					throw new IllegalStateException(s"Location $fromLoc is not empty to receive load ${tr.load} from channel ${from.channelName}")
				}
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for load from channel ${from.channelName}")
		}
	}

	def waitingForSlot(shuttle: ProcessorRef, to: Channel.Start[MaterialLoad, ShuttleLevelMessage], toLoc: ShuttleLocation)(continue: Processor.DomainRun[ShuttleLevelMessage]): Processor.DomainRun[ShuttleLevelMessage] = {
		implicit ctx: Processor.CommandContext[ShuttleLevelMessage] => {
			case tr: Channel.AcknowledgeLoad[MaterialLoad] =>
				ctx.tellTo(shuttle, Shuttle.Unload(toLoc))
				unloading(continue)
			case other =>
				throw new IllegalArgumentException(s"Unknown signal received $other when waiting for Shuttle to arrive to delivery location")
		}
	}
}
