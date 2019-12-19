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
import com.saldubatech.base.layout.Geography.{ClosedPathGeography, ClosedPathPoint, Length}
import com.saldubatech.base.layout.TaggedGeography
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.units.unitsorter.CircularSorterExecution
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.builders.ChannelBuilder.{IO, IOChannels}
import com.saldubatech.model.configuration.Sorting.CircularSorter
import com.saldubatech.util.Lang._


object CircularSorterBuilder {

}

class CircularSorterBuilder(conf: CircularSorter)(implicit gw: Gateway,
                          override protected val channelRegistry: Registry[DirectedChannel[Material]],
                          override protected val elementRegistry: Registry[ActorRef]) extends Builder {

	private def findChannels(binding: Map[String, String]):
	Map[String, DirectedChannel[Material]] = {
		binding.map {
				case (epTag, _) => epTag -> channelRegistry.lookupByName(epTag)
			}.filter{
				case (_, channelOption) => channelOption.isDefined
			}.map{case (k, ov) => k -> ov.head}
	}

	private def findPoints(binding: Map[String, String]) = {
		binding.map {
				case (epTag, sorterBinding) => epTag -> conf.inductPoints.get(sorterBinding)
			}.filter{case (k, v) => v isDefined}.map{case (k,v) => k -> v.head}
	}


	def build(name: String,
	         // External to Internal Input Names
	          inducts: Map[String, String],
	          discharges: Map[String, String]
	         ): Option[IO] = {
		val tk = elementRegistry.reserve(name)
		if(tk isDefined) {
			// External Name to Channel
			val inductChannels = findChannels(inducts)
			assert(inductChannels.size == inducts.size, "Could not find all induct channels for building Circular Sorter: $name")
			val dischargeChannels = findChannels(discharges)
			assert(dischargeChannels.size == discharges.size, "Could not find all induct channels for building Circular Sorter: $name")

			// External name to point index
			val inductBindings = findPoints(inducts)
			val dischargeBindings = findPoints(discharges)
			assert(inductBindings.size != inducts.size, "Did not find all Induct Bindings")
			assert(dischargeBindings.size != discharges.size, "Did not find all Discharge Bindings")

			val geo: ClosedPathGeography = new ClosedPathGeography(conf.physics.nTrays)

			implicit val  pathLength = Length(conf.physics.nTrays)

			val geoTags = Map.empty[DirectedChannel.Endpoint[Material], ClosedPathPoint].empty ++ // Here to enforce the typing by compiler.
				inductBindings.map{
				case (epTag, pointIndex) => inductChannels(epTag).end -> ClosedPathPoint(pointIndex)
			} ++
				dischargeBindings.map{
				case (epTag, pointIndex) => dischargeChannels(epTag).start -> ClosedPathPoint(pointIndex)
			}

			val taggedGeo: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint] =
				new TaggedGeography.Impl(geoTags, geo)

			val cc = gw.simActorOf(Props(
				new CircularSorterExecution(
					name,
					inductChannels.values.toList,
					dischargeChannels.values.toList,
					taggedGeo,
					conf.physics
				)), name)
			elementRegistry.register(tk.!, cc)
			lastBuilt = List(tk.!)

			Some(IO(inductChannels.values, dischargeChannels.values))
		} else None
	}
}
