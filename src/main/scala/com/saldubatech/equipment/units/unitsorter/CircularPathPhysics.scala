/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.equipment.units.unitsorter

import com.saldubatech.base.layout.Geography.{ClosedPathGeography, ClosedPathPoint, LinearPoint}
import com.typesafe.scalalogging.Logger

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
	import com.saldubatech.base.layout.Geography._

	private val logger = Logger(this.getClass.toString)

	private var tray0Offset: Long = tray0Location
	private var refLength: Length = Length(trayLength)
	private var asOf: Long = 0
	def currentTime: Long = asOf

	def updateLocation(at: Long): Long = {
		assert(at >= asOf, s"Time cannot go backwards from $asOf to $at")
		// Calculation done always from origin of time to avoid accumulation of rounding errors
		tray0Offset = (tray0Location + distanceTraveled(at)/trayLength) % nTrays
		logger.debug(s"Updating location to $tray0Offset at time $at from $asOf")
		asOf = at
		tray0Offset
	}

	def indexForElement(element: Int): ClosedPathPoint = {
		assert(element < nTrays, s"Index $element is bigger than the number of trays: $nTrays")
		val result = new ClosedPathPoint(element+tray0Offset)
		logger.debug(s"Computing Index for $element with offset $tray0Offset = $result as of $asOf")
		result
	}

	def pointAtIndex(index: ClosedPathPoint): ClosedPathPoint =  {
		new ClosedPathPoint(index - tray0Offset)
	}

	def distanceFromNumber(fromNumber: Int, toIndex: ClosedPathPoint): Long =
		distance(indexForElement(fromNumber), toIndex)

	def estimateElapsed(fromIndex: ClosedPathPoint, toIndex: ClosedPathPoint): Long =
		Math.round(trayLength*distance(fromIndex, toIndex).toDouble/speed.toDouble)

	def estimateElapsedFromNumber(fromNumber: Int, toIndex: ClosedPathPoint): Long =
		estimateElapsed(indexForElement(fromNumber), toIndex)

	def distanceTraveled(timeElapsed: Long): Long = timeElapsed*speed
}
