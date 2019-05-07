/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.circularsorter

import com.saldubatech.base.Material
import com.saldubatech.base.Processor.ExecutionResourceImpl
import com.saldubatech.utils.Boxer._

object Tray {
	def apply(number: Long): Tray = new Tray(number)
}

// This can be made a bit more sophisticated in terms of reservations
class Tray(val number: Long) extends ExecutionResourceImpl(number.toString) {
	var slot: Option[Material] = None
	var isAssigned = false
	def reserve: Boolean = if(isAssigned) false else {isAssigned = true; true}
	def free: Boolean = if(isEmpty) {isAssigned = false; true} else false

	def isEmpty: Boolean = slot.isEmpty

	def <<(contents: Material): Boolean = if(slot isEmpty) {reserve;slot = contents.?; true} else false
	def >>(): Option[Material] = {val result = slot; slot = None; free ;result}
}
