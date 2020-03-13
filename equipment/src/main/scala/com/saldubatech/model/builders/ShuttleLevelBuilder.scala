/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.model.builders

import akka.actor.{ActorRef, Props}
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.units.shuttle.ShuttleLevelExecutor
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.model.configuration.ShuttleStorage.ShuttleLevel
import com.saldubatech.utils.Boxer._

object ShuttleLevelBuilder {

	def apply(conf: ShuttleLevel)(implicit gw: Gateway,
	                              channelRegistry: Registry[DirectedChannel[Material]],
	                              elementRegistry: Registry[ActorRef]) =
		new ShuttleLevelBuilder(conf)

}
class ShuttleLevelBuilder(conf: ShuttleLevel)(implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {
	def build(name: String): Option[IOChannels] = {
		log.debug(s"Reserving $name")
		val tk = elementRegistry.reserve(name)
		log.debug(s"Reserved $tk")
		if (tk isDefined) {
			val inboundChannel = ChannelBuilder(conf.inbound).build(name+ "<I>")
			val outboundChannel = ChannelBuilder(conf.outbound).build(name + "<O>")
			if (inboundChannel.isDefined && outboundChannel.isDefined) {
				lastBuilt = List(tk.!)
				elementRegistry.register(tk.!, gw.simActorOf(Props(
					new ShuttleLevelExecutor(
						name,
						conf.physics,
						conf.aisleLength,
						inboundChannel.!,
						outboundChannel.!,
						conf.initialPosition,
						Map.empty)), name))
				IOChannels(inboundChannel.!, outboundChannel.!).?
			} else {
				elementRegistry.release(tk.!)
				None
			}
		} else None
	}
}
