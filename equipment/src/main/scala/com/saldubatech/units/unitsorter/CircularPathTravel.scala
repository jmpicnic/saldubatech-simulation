/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.units.unitsorter

import com.saldubatech.ddes.Clock.{Delay, Tick}

object CircularPathTravel {
	def apply(
		         nTrays: Int,
		         speed: Int,
		         trayLength: Int): CircularPathTravel =
		new CircularPathTravel(nTrays, speed, trayLength)

}

class CircularPathTravel(val nSlots: Int,
                          speed: Int, // length Units/tick
                          trayLength: Int) {

	private val slotSpeed = speed.toDouble/trayLength.toDouble
	val oneTurnTime: Delay = Math.ceil(nSlots.toDouble/slotSpeed).toLong

	class Position(at: Tick) {
		val zeroIndex = Math.floor(at.toDouble * slotSpeed).toInt % nSlots
		val slotAtZero = nSlots - zeroIndex
		def slotAtIndex(index: Int) = (index + nSlots - zeroIndex) % nSlots

		def indexForSlot(index: Int) = (index + zeroIndex) % nSlots
	}

	def travelTime(from: Int, to: Int): Delay = {
		val distance = (to + nSlots - from) % nSlots
		Math.ceil(distance.toDouble/slotSpeed).toLong
	}
}
