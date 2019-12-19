/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.resource

import com.saldubatech.base.Material
import com.saldubatech.util.Lang._

object Slot {
	def apply[M <: Material](id: String = java.util.UUID.randomUUID.toString): Slot[M] = new Slot(id)
}

// This can be made a bit more sophisticated in terms of reservations
class Slot[M <: Material](val id: String= java.util.UUID.randomUUID.toString) extends Resource.Impl(id) {
	var slot: Option[M] = None
	var isAssigned = false
	def reserve: Boolean = if(isAssigned) false else {isAssigned = true; true}
	def free: Boolean = if(isIdle) {isAssigned = false; true} else false

	def isIdle: Boolean = slot.isEmpty
	override def isBusy: Boolean = !isIdle
	override def isInUse: Boolean = false

	def <<(contents: M): Boolean = if(slot isEmpty) {reserve; slot = contents.?; true} else false
	def >>(): Option[M] = {val result = slot; slot = None; free ;result}
}
