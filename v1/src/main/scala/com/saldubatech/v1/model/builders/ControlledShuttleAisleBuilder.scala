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
import com.saldubatech.v1.ddes.Gateway
import com.saldubatech.v1.equipment.composites.controllers.ShuttleAisleController
import com.saldubatech.v1.model.builders.Builder.Registry
import com.saldubatech.v1.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.v1.model.configuration.Layout.TransportLink
import com.saldubatech.v1.model.configuration.ShuttleStorage.ShuttleAisle
import com.saldubatech.util.Lang._

object ControlledShuttleAisleBuilder {
	def apply(conf: ShuttleAisle)(implicit gw: Gateway,
	                              channelRegistry: Registry[DirectedChannel[Material]],
	                              elementRegistry: Registry[ActorRef]) =
		new ControlledShuttleAisleBuilder(conf)
}

class ControlledShuttleAisleBuilder(conf: ShuttleAisle)(implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {
	private val aisleBuilder = ShuttleAisleBuilder(conf)

	//private val levelBuilders: List[ShuttleLevelBuilder] = conf.levels.map(ShuttleLevelBuilder(_))
	//private val liftBuilder : LiftBuilder = LiftBuilder(conf.lift)

	private def levelName(rootName: String, idx: Int): String = rootName+f"_Level$idx%02d"

	// Should do a best effort in "clean up" and return None instead of throwing an exception
	def build(name: String, inbound: TransportLink, outbound: TransportLink, initialLiftPosition: Int = conf.lift.ioLevel): Option[IOChannels] = {
		val tk  = elementRegistry.reserve(name)
		if(tk isDefined) {

			val ioChannels = aisleBuilder.build(name, inbound, outbound, initialLiftPosition)

			val levels = conf.levels.indices.map(idx => aisleBuilder.findElement(levelName(name, idx)).!).toList
			val levelIo = conf.levels.indices.map(idx => IOChannels(
				aisleBuilder.findChannel(levelName(name, idx)+"<I>").!,
				aisleBuilder.findChannel(levelName(name, idx)+"<O>").!
			)).toList

			val aisleController = ShuttleAisleController(
				name, ioChannels.!.in, ioChannels.!.out,
				levels, levelIo.map(_.in), levelIo.map(_.out),
				elementRegistry.lookupByName(name + "_Lift").!)

			elementRegistry.register(tk.!, aisleController)
			lastBuilt = tk.toList
			IOChannels(ioChannels.!.in, ioChannels.!.out).?
		} else None
	}
}
