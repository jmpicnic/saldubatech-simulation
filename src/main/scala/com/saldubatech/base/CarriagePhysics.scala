/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

object CarriagePhysics{
	def apply(acquireTime: Int,
	          releaseTime: Int,
	          latencyPerPosition: Int,
	          accelerationRunway: Int,
            stoppingRunway: Int
	         ): CarriagePhysics =
		new CarriagePhysics(acquireTime, releaseTime, latencyPerPosition, accelerationRunway, stoppingRunway)
}

class CarriagePhysics(
                     val acquireTime: Long,
                     val releaseTime: Long,
                     latencyPerPosition: Long,
                     accelerationRunway: Long,
                     stoppingRunway: Long
                     ) {
	private val transientD = accelerationRunway + stoppingRunway

	def accelerationTime(d: Long): Long = {
		val distance = Math.abs(d)
		val max = 2 * accelerationRunway * latencyPerPosition
		if (distance >= transientD) max
		else Math.round(max.toDouble * Math.sqrt(distance.toDouble / transientD.toDouble))
	}
	def stoppingTime(d: Long): Long = {
		val distance = Math.abs(d)
		val max = 2*stoppingRunway*latencyPerPosition
		if (distance >= transientD) max
		else Math.round(max.toDouble * Math.sqrt(distance.toDouble / transientD.toDouble))
	}

	def transientTime(d: Long): Long = {
		val max = 2*transientD*latencyPerPosition
		val distance = Math.abs(d)
		if (distance >= transientD) max
		else Math.round(max.toDouble * Math.sqrt(distance.toDouble / transientD.toDouble))
	}
	def travelTime(d: Long): Long = {
		val distance = Math.abs(d)
		val cruisePositions = Math.max(0, Math.abs(distance)-transientD)
		transientTime(distance) + (if(cruisePositions > 0) latencyPerPosition*cruisePositions else 0)
	}

	def timeToStage(distance: Long): Long = acquireTime + travelTime(distance)
	def timeToDeliver(distance: Long): Long = releaseTime + travelTime(distance)

}
