/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events

object EventTypeEnum{

}

class EventTypeEnum(_category: EventCategory.Value) extends Enumeration {
	class CategorizedVal extends Val {
		val category: EventCategory.Value = _category
	}

}
