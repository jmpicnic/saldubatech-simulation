/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events


object OperationalEvent extends EventTypeEnum(EventCategory.OPERATIONAL) {
		val New, Arrive, Start, Complete, Depart, End = new CategorizedVal()

	def apply(evId: String, at: Long, opEvType: OperationalEvent.Value, station: String, load: String): Event =
		new Event(evId, at, EventCategory.OPERATIONAL, opEvType, station, load)

	def apply(at: Long, opEvType: OperationalEvent.Value, station: String, load: String): Event =
		new Event(java.util.UUID.randomUUID.toString, at, EventCategory.OPERATIONAL, opEvType, station, load)

	val complementary: Map[OperationalEvent.Value,OperationalEvent.Value] = Map[OperationalEvent.Value, OperationalEvent.Value](
		(OperationalEvent.New, OperationalEvent.End), (OperationalEvent.End,OperationalEvent.New),
		(OperationalEvent.Start, OperationalEvent.Complete), (OperationalEvent.Complete, OperationalEvent.Start),
		(OperationalEvent.Arrive, OperationalEvent.Depart), (OperationalEvent.Depart, OperationalEvent.Arrive))
	val after: Map[OperationalEvent.Value,OperationalEvent.Value] = Map[OperationalEvent.Value, OperationalEvent.Value](
		(OperationalEvent.Arrive, OperationalEvent.Depart), (OperationalEvent.Start, OperationalEvent.Arrive),
		(OperationalEvent.Complete, OperationalEvent.Start), (OperationalEvent.Depart, OperationalEvent.Complete))
	val before: Map[OperationalEvent.Value,OperationalEvent.Value] = after.map(e => (e._2,e._1))

}

