/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.model.configuration

import java.io

import com.saldubatech.randomvariables.Distributions._
object Layout {

	object TransportLink {
		def apply(from: String, to: String, capacity: Int, nEndpoints: Int = 1, delay: LongRVar = zeroLong) =
			new TransportLink(from, to, capacity, nEndpoints, delay)
	}
	case class TransportLink(from: String, to: String, capacity: Int, nEndpoints: Int = 1, delay: LongRVar = zeroLong)


}

class Layout(links: List[Layout.TransportLink]) {
	import com.saldubatech.model.configuration.Layout.TransportLink
	val fromLinks: Map[String, Map[String, TransportLink]] = links.groupBy(_.from).map{case (k, v) => k -> Map(v.map(le => le.to -> le): _*)}
	val toLinks: Map[String, Map[String, TransportLink]] = links.groupBy(_.to).map{case (k, v) => k -> Map(v.map(le => le.from -> le): _*)}


//
	def efferent(from: String): Iterable[TransportLink] = fromLinks.getOrElse(from, Map()).values
	def afferent(to: String): Iterable[TransportLink] = toLinks.getOrElse(to, Map()).values
	def link(from: String, to: String): Option[TransportLink] = fromLinks.get(from).flatMap(m => m.get(to))
}