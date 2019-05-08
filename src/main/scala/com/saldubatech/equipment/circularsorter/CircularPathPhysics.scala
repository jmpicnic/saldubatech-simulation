/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.circularsorter

import com.saldubatech.physics.Geography.{ClosedPathGeography, ClosedPathPoint, LinearPoint}

object CircularPathPhysics {
	def apply(
		         nTrays: Int,
		         speed: Int,
		         trayLength: Int,
		         tray0Location: Int): CircularPathPhysics =
		new CircularPathPhysics(nTrays, speed, trayLength, tray0Location)

	class CloserIndex(physics: CircularPathPhysics, position: Long) extends Ordering[ClosedPathPoint] {
		override def compare(x: ClosedPathPoint, y: ClosedPathPoint): Int = physics.distance(x, y).toInt
	}
}

class CircularPathPhysics(val nTrays: Int,
                          speed: Int, // positions/tick
                          trayLength: Int,
                          tray0Location: Int)
	extends ClosedPathGeography(nTrays) {
	import com.saldubatech.physics.Geography._

	private var tray0Offset: Long = tray0Location
	private var asOf: Long = 0
	def currentTime: Long = asOf

	def updateLocation(at: Long): Long = {
		assert(at > asOf, "Time cannot go backwards")
		// Calculation done always from origin of time to avoid accumulation of rounding errors
		tray0Offset = (tray0Location + distanceTraveled(at)/trayLength) % nTrays
		asOf = at
		tray0Offset
	}

	def indexForElement(element: Int): ClosedPathPoint = {
		assert(element < nTrays, s"Index $element is bigger than the number of trays: $nTrays")
		new ClosedPathPoint(element+tray0Offset)
	}

	def pointAtIndex(index: ClosedPathPoint): ClosedPathPoint =  {
		new ClosedPathPoint(index - tray0Offset)
	}

	def estimateElapsed(fromIndex: ClosedPathPoint, toIndex: ClosedPathPoint): Long = {
		Math.round(trayLength*distance(fromIndex, toIndex).toDouble/speed.toDouble)
	}
	def estimateElapsedFromNumber(fromNumber: Int, toIndex: ClosedPathPoint): Long = {
		estimateElapsed(indexForElement(fromNumber), toIndex)
	}

	def distanceTraveled(timeElapsed: Long): Long = timeElapsed*speed
}
