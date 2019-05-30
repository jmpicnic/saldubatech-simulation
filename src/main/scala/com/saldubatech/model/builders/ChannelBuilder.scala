/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.model.builders

import akka.actor.ActorRef
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.configuration.Layout.TransportLink
import com.saldubatech.randomvariables.Distributions._
import com.saldubatech.utils.Boxer._

object ChannelBuilder {

	case class IOChannels(in: DirectedChannel[Material], out: DirectedChannel[Material]) {
		def reverse = IOChannels(out, in)
	}

	case class IO(inducts: Iterable[DirectedChannel[Material]], discharges: Iterable[DirectedChannel[Material]]) {
		val reverse = IO(discharges, inducts)
	}

	def apply(link: TransportLink)(implicit channelRegistry:Registry[DirectedChannel[Material]]) = new ChannelBuilder(link.capacity, link.delay)

	def apply(capacity: Int, delay: LongRVar = zeroLong)(implicit channelRegistry:Registry[DirectedChannel[Material]]) = new ChannelBuilder(capacity, delay)
}

case class ChannelBuilder(capacity: Int, delay: LongRVar = zeroLong)
                         (implicit override protected val channelRegistry:Registry[DirectedChannel[Material]])
	extends Builder {
	override protected val elementRegistry: Registry[ActorRef] = null

	def build(name: String): Option[DirectedChannel[Material]] = {
		val tk = channelRegistry.reserve(name)
		if (tk isDefined) {
			val c = DirectedChannel[Material](capacity, name, delay)
			channelRegistry.register(tk.!, c)
			lastBuilt = List(tk.!)
			c.?
		} else None
	}
}
