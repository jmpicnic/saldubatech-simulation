/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.model.builders

import akka.actor.ActorRef
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.model.builders.Builder.Registry
import com.saldubatech.v1.model.configuration.Layout.TransportLink
import com.saldubatech.v1.randomvariables.Distributions._
import com.saldubatech.util.Lang._

object ChannelBuilder {

	case class IOChannels(in: DirectedChannel[Material], out: DirectedChannel[Material]) {
		def reverse = IOChannels(out, in)
	}

	case class IO(inducts: Iterable[DirectedChannel[Material]], discharges: Iterable[DirectedChannel[Material]]) {
		val reverse = IO(discharges, inducts)
	}

	def apply(link: TransportLink)(implicit channelRegistry:Registry[DirectedChannel[Material]])
	= new ChannelBuilder(link.capacity, link.nEndpoints, link.delay)

	def apply(capacity: Int, nEndpoints: Int = 1, delay: LongRVar = zeroLong)
	         (implicit channelRegistry:Registry[DirectedChannel[Material]])
	= new ChannelBuilder(capacity, nEndpoints, delay)
}

case class ChannelBuilder(capacity: Int, nEndpoints: Int = 1, delay: LongRVar = zeroLong)
                         (implicit override protected val channelRegistry:Registry[DirectedChannel[Material]])
	extends Builder {
	override protected val elementRegistry: Registry[ActorRef] = null

	def build(name: String): Option[DirectedChannel[Material]] = {
		val tk = channelRegistry.reserve(name)
		if (tk isDefined) {
			val c = DirectedChannel[Material](capacity, name, nEndpoints, delay)
			channelRegistry.register(tk.!, c)
			lastBuilt = List(tk.!)
			c.?
		} else None
	}
}
