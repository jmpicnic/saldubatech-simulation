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

package com.saldubatech.equipment.circularsorter

import com.saldubatech.physics.Geography.{ClosedPathPoint, Length}
import com.saldubatech.randomvariables.Distributions._
import com.saldubatech.test.utils.BaseSpec


class CircularPathPhysicsSpec extends BaseSpec {
	val nTrays: Int = 12
	implicit val pathLength: Length = Length(nTrays)
	val speed: Int = 3
	val trayLength: Int = 6
	val tray0Location: Int = 3
	val timeToLoad: Int = 22
	val timeToDischarge: Int = 33
	var underTest = CircularPathPhysics(
		nTrays,
		speed,
		trayLength,
		tray0Location)
	"A CircularPathPhysics" when {
		"just initialized" should {
			"keep the number of trays value invariant and time = 0" in {
				underTest.nTrays shouldBe nTrays
				underTest.currentTime shouldBe 0
			}
			"have all indexes offset by the value of the tray0Location" in {
				for(idx <- 0 until underTest.nTrays)
					underTest.indexForElement(idx).coord shouldBe (idx + tray0Location)%underTest.nTrays
			}
			"have all number offset by the negative of the tray0Location" in {
				for(idx <- 0 until underTest.nTrays)
					underTest.pointAtIndex(idx).coord shouldBe (idx - tray0Location+nTrays)%underTest.nTrays
			}
			"Traverse speed/(trayLenth*time) in a given time" in {
				val clockTime = 20
				val traversedDistance = speed * clockTime
				val nTraysMoved = traversedDistance/trayLength
				underTest.updateLocation(clockTime)
				underTest.currentTime shouldBe clockTime
				underTest.distanceTraveled(clockTime) shouldBe 60
				underTest.indexForElement(0).coord shouldBe (tray0Location + nTraysMoved)%underTest.nTrays
				underTest.updateLocation(21)
				underTest.indexForElement(0).coord shouldBe (tray0Location + speed*21/trayLength)%underTest.nTrays
				underTest.updateLocation(22)
				underTest.indexForElement(0).coord shouldBe (tray0Location + speed*22/trayLength)%underTest.nTrays
				underTest.updateLocation(23)
				underTest.indexForElement(0).coord shouldBe (tray0Location + speed*23/trayLength)%underTest.nTrays
				underTest.updateLocation(24)
				underTest.indexForElement(0).coord shouldBe (tray0Location + speed*24/trayLength)%underTest.nTrays
			}
			"Estimate the number of ticks requried for a tray to go to from a fixed index to another" in {
				val current0Index = underTest.indexForElement(0)
				underTest.distance(new ClosedPathPoint(1), new ClosedPathPoint(10)) shouldBe 9
				underTest.distance(new ClosedPathPoint(10), new ClosedPathPoint(1)) shouldBe 3
				underTest.estimateElapsed(1, 10) shouldBe 9*trayLength/speed
			}
			"Estimate the number of ticks required for a tray with a number to go to a fixed index" in {
				val current0Index = underTest.indexForElement(0)
				underTest.estimateElapsedFromNumber(4, 10) shouldBe ((10 - current0Index-4)%nTrays)*trayLength/speed
			}
			"Special Case" in {
				val ut2 = CircularPathPhysics(12, 10, 40, 11)
				ut2.updateLocation(360) shouldBe 5
				ut2.indexForElement(1).coord shouldBe 6
				ut2.distance(new ClosedPathPoint(6), new ClosedPathPoint(6)) shouldBe 0
				ut2.indexForElement(9).coord shouldBe 2
			}
		}
	}

}
