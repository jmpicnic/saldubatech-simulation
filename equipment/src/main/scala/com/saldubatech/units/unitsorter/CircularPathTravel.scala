/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.units.unitsorter

import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.typesafe.scalalogging.Logger

object CircularPathTravel {
	def apply(
		         nTrays: Int,
		         speed: Int,
		         trayLength: Int): CircularPathTravel =
		new CircularPathTravel(nTrays, speed, trayLength)

}

class CircularPathTravel(nSlots: Int,
                          speed: Int, // length Units/tick
                          trayLength: Int) {

	private val slotSpeed = speed.toDouble/trayLength.toDouble

	def zeroPosition(at: Tick): Int = (at % (oneTurnTime)).toInt

	val oneTurnTime: Delay = Math.round(nSlots.toDouble*trayLength.toDouble/speed.toDouble)

	def travelTime(from: Int, to: Int): Delay = {
		val distance = (to + nSlots - from) % nSlots
		Math.round(distance.toDouble/slotSpeed)
	}
}
