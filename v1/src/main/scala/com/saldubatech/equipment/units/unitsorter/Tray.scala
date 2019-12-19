/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.unitsorter

import com.saldubatech.base.Material
import com.saldubatech.base.resource.Resource
import com.saldubatech.util.Lang._

object Tray {
	def apply(number: Int): Tray = new Tray(number)
}

// This can be made a bit more sophisticated in terms of reservations
class Tray(val number: Int) extends Resource.Impl(number.toString) {
	var slot: Option[Material] = None
	var isAssigned = false
	def reserve: Boolean = if(isAssigned) false else {isAssigned = true; true}
	def free: Boolean = if(isIdle) {isAssigned = false; true} else false

	def isIdle: Boolean = slot.isEmpty
	override def isBusy: Boolean = !isIdle
	override def isInUse: Boolean = false

	def <<(contents: Material): Boolean = if(slot isEmpty) {reserve;slot = contents.?; true} else false
	def >>(): Option[Material] = {val result = slot; slot = None; free ;result}

}
