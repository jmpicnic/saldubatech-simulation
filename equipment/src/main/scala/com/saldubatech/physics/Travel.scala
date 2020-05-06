/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.physics

import com.saldubatech.ddes.Clock.Delay

object Travel {
	type Speed = Double
	type Distance = Int

	def apply(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay) = new Travel(distancePerTick, rampUpLength, rampDownLength)
}
import com.saldubatech.physics.Travel._

class Travel(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay) {
	private val rampLength = (rampUpLength + rampDownLength).toDouble
	private val maxRampTime: Delay = 2*Math.round(rampLength/distancePerTick)

	def travelTime(distance: Distance): Delay = {
		val d = Math.abs(distance.toDouble)
		if (d >= rampLength) maxRampTime + Math.round((d - rampLength)/distancePerTick)
		else Math.round(maxRampTime * Math.sqrt(d / rampLength.toDouble))
	}
}
