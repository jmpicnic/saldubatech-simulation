/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.model.builders

import akka.actor.ActorRef
import com.saldubatech.base.Aisle.{LevelLocator, Locator}
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.composites.controllers.ShuttleAisleController
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.model.configuration.Layout.TransportLink
import com.saldubatech.model.configuration.ShuttleStorage.ShuttleAisle
import com.saldubatech.utils.Boxer._

object ShuttleAisleBuilder {
	def apply(conf: ShuttleAisle)(implicit gw: Gateway,
	                              channelRegistry: Registry[DirectedChannel[Material]],
	                              elementRegistry: Registry[ActorRef]) =
		new ShuttleAisleBuilder(conf)
}

class ShuttleAisleBuilder(conf: ShuttleAisle)
                         (implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {
	private val levelBuilders: List[ShuttleLevelBuilder] = conf.levels.map(ShuttleLevelBuilder(_))
	private val liftBuilder : LiftBuilder = LiftBuilder(conf.lift)

	private def levelName(rootName: String, idx: Int): String = rootName+f"_Level$idx%02d"

	// Should do a best effort in "clean up" and return None instead of throwing an exception
	def build(name: String, inbound: TransportLink, outbound: TransportLink, initialLiftPosition: Int = conf.lift.ioLevel): Option[IOChannels] = {

		val inboundChannel = ChannelBuilder(inbound).build(name + "<I>")
		val outboundChannel = ChannelBuilder(outbound).build(name + "<O>")

		val levelIo: List[IOChannels] = levelBuilders.zipWithIndex.flatMap {
			case (level, idx) => level.build(levelName(name, idx))
		}

		if ((levelIo.length != levelBuilders.length) || inboundChannel.isEmpty || outboundChannel.isEmpty)
			throw new InternalError(s"Something went wrong in the greation of the shuttle levels for conf: $conf with name $name")

		val levels = (0 until levelBuilders.length).map(idx => elementRegistry.lookupByName(levelName(name, idx)).!).toList
		if (liftBuilder.build(
			name + "_Lift",
			levelIo,
			inboundChannel.!.uid,
			outboundChannel.!.uid,
			initialLiftPosition) isEmpty)
			throw new InternalError(s"Something went wrong in the creation of the lift for conf: $conf with name $name")

		IOChannels(inboundChannel.!, outboundChannel.!).?

	}
}
