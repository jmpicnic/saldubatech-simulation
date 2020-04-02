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
import com.saldubatech.v1.ddes.Gateway
import com.saldubatech.v1.model.builders.Builder.Registry
import com.saldubatech.v1.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.v1.model.configuration.Layout.TransportLink
import com.saldubatech.v1.model.configuration.ShuttleStorage.ShuttleStore

class ShuttleStoreBuilder(conf: ShuttleStore)(implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {
	private val aisleBuilders = conf.aisles.map(aisleConf => new ShuttleAisleBuilder(aisleConf))

	def build(name: String, inbound: List[TransportLink], outbound: List[TransportLink]): List[IOChannels] = {

		val aisleIOChannels = aisleBuilders.zipWithIndex.flatMap {
			case (builder, idx) => builder.build(f"$name$idx%2d", inbound(idx), outbound(idx))
		}

		assert(aisleIOChannels.size == aisleBuilders.size, s"Could not build all the aisles for ShuttleStore $name")
		aisleIOChannels
	}
}
