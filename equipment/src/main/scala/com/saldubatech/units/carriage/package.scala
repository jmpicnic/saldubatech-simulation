package com.saldubatech.units

import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.physics.Travel
import com.saldubatech.physics.Travel.Speed

package object carriage {
	sealed trait SlotLocator{val idx: Int}
	case class At(override val idx: Int) extends SlotLocator
	case class OnRight(override val idx: Int) extends SlotLocator
	case class OnLeft(override val idx: Int) extends SlotLocator

	object CarriageTravel {
		def apply(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, acquireTime: Delay, releaseTime: Delay) =
			new CarriageTravel(distancePerTick, rampUpLength,rampDownLength, acquireTime, releaseTime)
	}

	class CarriageTravel(distancePerTick: Speed, rampUpLength: Delay, rampDownLength: Delay, val acquireTime: Delay, val releaseTime: Delay, interSlotDistance: Int = 1) extends Travel(distancePerTick, rampUpLength, rampDownLength) {
		def travelTime(from: Int, to: Int): Delay = travelTime(interSlotDistance*from - interSlotDistance*to)
		def timeToPickup(from: SlotLocator, to: SlotLocator): Delay = acquireTime + travelTime(from.idx, to.idx)
		def timeToDeliver(from: SlotLocator, to: SlotLocator): Delay = releaseTime + travelTime(from.idx, to.idx)
	}

}
