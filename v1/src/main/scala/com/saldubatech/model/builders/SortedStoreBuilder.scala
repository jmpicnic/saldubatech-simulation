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
import com.saldubatech.ddes.Gateway
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.builders.ChannelBuilder.{IO, IOChannels}
import com.saldubatech.model.configuration.GTP.SortedStore
import com.saldubatech.model.configuration.Layout.TransportLink
import com.saldubatech.util.Lang._

class SortedStoreBuilder(conf: SortedStore)(implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {
	val sorterBuilder = new CircularSorterBuilder(conf.sorter)
	val storeBuilder = new ShuttleStoreBuilder(conf.store)


	def build(name: String,
	          sorterToStore: List[TransportLink],
	          storeToSorter: List[TransportLink],
	          inboundLinks: List[TransportLink],
	          outboundLinks: List[TransportLink],
	          sorterInductBindings: Map[String, String],
	          sorterDischargeBindings: Map[String, String]): Option[IO] = {

		val storeChannels: List[IOChannels] = storeBuilder.build(name+"_Store", sorterToStore, storeToSorter)
		val sorterToStoreChannels = storeChannels.map(cp => cp.in)
		val storeToSorterChannels = storeChannels.map(cp => cp.out)

		val sorterIO: Option[IO] = sorterBuilder.build(name+"_sorter", sorterInductBindings, sorterDischargeBindings)

		if(sorterIO isDefined) Some(IO(
			sorterIO.!.inducts.filter(!storeToSorterChannels.contains(_)),
			sorterIO.!.discharges.filter(!sorterToStoreChannels.contains(_))))
		else None
	}
}
