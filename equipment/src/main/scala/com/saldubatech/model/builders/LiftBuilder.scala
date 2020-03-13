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
import com.saldubatech.equipment.units.shuttle.LiftExecutor
import com.saldubatech.model.builders.Builder.{Registry, Token}
import com.saldubatech.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.model.configuration.ShuttleStorage.Lift
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object LiftBuilder {
	def apply(conf: Lift)(implicit gw: Gateway,
	                              channelRegistry: Registry[DirectedChannel[Material]],
	                              elementRegistry: Registry[ActorRef]) = new LiftBuilder(conf)
}

class LiftBuilder(conf: Lift)
                 (implicit gw: Gateway,
                  override protected val channelRegistry: Registry[DirectedChannel[Material]],
                  override protected val elementRegistry: Registry[ActorRef]) extends Builder {

	def buildFromNames(name: String, levelNames: List[String],
	                   inboundChannelName: String,
		                 outboundChannelName: String,
		                 initialPosition: Int): Option[IOChannels] = {
		val tk = elementRegistry.reserve(name)
		if (tk isDefined) {
			val levelChannels = levelNames.flatMap(n => {
				val in = channelRegistry.lookupByName(n + "<I>")
				val out = channelRegistry.lookupByName(n + "<O>")
				if (in.isEmpty || out.isEmpty) None
				else IOChannels(in.!, out.!).?
			})
			assert(levelChannels.size == levelNames.size, s"Cannot find all the shuttle channels building lift $name")
			buildInternal(tk.!, levelChannels, inboundChannelName, outboundChannelName, initialPosition)
		} else None
	}
	def build(name: String, levelChannels: List[IOChannels],
	          inboundChannelName: String,
		        outboundChannelName: String,
		        initialPosition: Int): Option[IOChannels] = {
		val tk = elementRegistry.reserve(name)
		if (tk isDefined) {
			buildInternal(tk.!, levelChannels, inboundChannelName, outboundChannelName, initialPosition)
		} else None
	}

	def buildInternal(tk: Token,
	                  levelChannels: List[IOChannels],
	                  inboundChannelName: String,
	                  outboundChannelName: String,
		                initialPosition: Int): Option[IOChannels] = {
			val inboundChannel = channelRegistry.lookupByName(inboundChannelName)
			assert(inboundChannel isDefined, s"Can't find the channel $inboundChannelName trying to build lift $tk.name")
			val outboundChannel = channelRegistry.lookupByName(outboundChannelName)
			assert(outboundChannel isDefined, s"Can't find the channel $inboundChannelName trying to build lift $tk.name")
			lastBuilt = List(tk)
			elementRegistry.register(tk,
				gw.simActorOf(Props(
					new LiftExecutor(tk.name,
						conf.physics,
						inboundChannel.!,
						outboundChannel.!,
						levelChannels.map(io => (io.in, io.out)),
						levelChannels(initialPosition).in.start,
						conf.ioLevel)), tk.name))
			IOChannels(inboundChannel.!, outboundChannel.!).?
		}
}
