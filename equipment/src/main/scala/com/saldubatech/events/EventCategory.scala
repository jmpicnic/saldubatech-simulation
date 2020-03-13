/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.events


object EventCategory extends Enumeration {
	val DUMMY, OPERATIONAL = Value

	def withCategory(cat: String): String => EventTypeEnum#Value = categories(EventCategory.withName(cat)).withName _


	private val categories =
		Map[EventCategory.Value,EventTypeEnum](
			(DUMMY, DummyEventType),
			(OPERATIONAL, OperationalEvent)
		)
}