/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.events




object Event {
	object Category extends Enumeration {
		val OPERATIONAL: Category.Value = Value
	}

	def apply(uid: String, ts: Long, evCategory: EventCategory.Value, evType: EventTypeEnum#Value, stationId: String, loadId: String): Event =
		new Event(uid, ts, evCategory, evType, stationId, loadId)

	def apply(ts: Long, evType: EventTypeEnum#CategorizedVal, stationId: String, loadId: String): Event =
		new Event(java.util.UUID.randomUUID.toString, ts, evType.category, evType, stationId, loadId)


//	def apply(ts: Long, evType: String): Event = builder(java.util.UUID.randomUUID().toString(), ts, evType)
//	def apply(tp: (String, Long, String)): Event = builder _ tupled tp//Event(tp._1, tp._2, tp._3)
//	private def builder(uid: String, ts: Long, evType: String): Event = Event(uid, ts, evType)
}

case class Event(uid: String, ts: Long, evCategory: EventCategory.Value, evType: EventTypeEnum#Value, stationId: String, loadId: String) {

	def asTuple: (String, Long, String, String, String, String) = (uid, ts, evCategory.toString, evType.toString, stationId, loadId)


	//def apply[T](f: (String, Long, String, String, String, String) => T): T = f tupled asTuple
	//def apply[T](f: Event => T): T = f(this asTuple)


}

