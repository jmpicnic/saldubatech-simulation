/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.base.layout

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.layout.Geography.Length
import com.saldubatech.util.Lang._

import scala.collection.mutable

object TopographyExp {

	abstract class ConnectionPort[N <: Identification](val host: N)
		extends Identification with LocationTag {
		val tag: LocationTag = this
	}

	trait InboundPort[N <: Identification] extends ConnectionPort[N]

	trait OutboundPort[N <: Identification] extends ConnectionPort[N]


	case class Node(id: String,
	           inPorts: Set[InboundPort[Node]],
	           outPorts: Set[OutboundPort[Node]])
		extends Identification {
		override protected def givenId: Option[String] = id.?

		private val outBindings = mutable.Map.empty[OutboundPort[Node], Link]

		def bind(outPort: OutboundPort[Node], link: Link): Unit = outBindings += (outPort -> link)

		def binding(outPort: OutboundPort[Node]): Option[Link] = outBindings.get(outPort)

		def downstream: Set[Node] = outPorts.map(p => binding(p)).flatMap(l => Some(l.head.end.host))

		def outLinks: Set[Link] = Set.empty[Link] ++ outBindings.values

		private val inBindings = mutable.Map.empty[InboundPort[Node], Link]

		def bind(inPort: InboundPort[Node], link: Link): Unit = inBindings += (inPort -> link)

		def binding(outPort: InboundPort[Node]): Option[Link] = inBindings.get(outPort)

		def upstream: Set[Node] = inPorts.map(p => binding(p)).flatMap(l => Some(l.head.end.host))

		def inLinks: Set[Link] = Set.empty[Link] ++ inBindings.values


	}

	case class Link(id: String, start: OutboundPort[Node], end: InboundPort[Node], length: Length)
		extends Identification {
		override protected def givenId: Option[String] = id.?
	}

}


class TopographyExp(nodes: Set[TopographyExp.Node]) {
	import TopographyExp._

	def links: Set[Link] = nodes.flatMap(n => n.outLinks ++ n.inLinks)
	private val distances: Map[(LocationTag, LocationTag), Length] =
		Map.empty ++ links.view.map(l => (l.start, l.end) -> l.length)

	val nodeByPort: Map[LocationTag, Node] =
		Map.empty ++ nodes.view.map(n => n.inPorts.view.map(p => p -> n) ++ n.outPorts.view.map(p => p -> n)).flatten

	def downStream(n: Node): Set[Node] =
		for(e <- links.collect{case Link(id, start, end, length) if n.outPorts.contains(start) => end})
			yield nodeByPort(e)

	def distance(from: LocationTag, to: LocationTag): Option[Length] = {
		distances.get((from, to))
	}
}
