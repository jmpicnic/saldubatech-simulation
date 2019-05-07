/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.circularsorter

object CircularPathPhysics {
		class CloserIndex(physics: CircularPathPhysics, position: Long) extends Ordering[Tray] {
			override def compare(x: Tray, y: Tray): Int = {
				(physics.distance(x.number, position) - physics.distance(y.number, position)).toInt
			}
		}
}

class CircularPathPhysics(val nTrays: Int,
                          speed: Int, // positions/tick
                          tray0Location: Int,
                          val timeToLoad : Long,
                          val timeToDischarge: Long
                         ) {
	private var tray0Index: Long = tray0Location
	private var asOf: Long = 0

	def updateLocation(at: Long): Long = {
		tray0Index = (tray0Index + distanceTraveled(at - asOf)) % nTrays
		asOf = at
		tray0Index
	}

	def indexForElement(element: Long): Long = {
		assert(element < nTrays, s"Index $element is bigger than the number of trays: $nTrays")
		(element+tray0Index)%nTrays
	}

	def elementAtIndex(index: Long): Long = {
		assert(index < tray0Index+nTrays && index > tray0Index, "Tray Index is out of range")
		val d = index - tray0Index
		if(d > 0) d else d + nTrays
	}

	def distance(fromIndex: Long, toIndex: Long): Long = {
		assert(fromIndex < tray0Index+nTrays && fromIndex > tray0Index, "from Tray Index is out of range")
		assert(toIndex < tray0Index+nTrays && toIndex > tray0Index, "to Tray Index is out of range")
		val d = toIndex - fromIndex
		if(d > 0) d else d + nTrays
	}
	def estimateElapsed(fromIndex: Long, toIndex: Long): Long = {
		Math.round(distance(fromIndex, toIndex).toDouble/speed.toDouble)
	}
	def estimateElapsedFromNumber(fromNumber: Long, toIndex: Long): Long = {
		estimateElapsed(indexForElement(fromNumber), toIndex)
	}

	def distanceTraveled(timeElapsed: Long): Long = timeElapsed*speed
}
