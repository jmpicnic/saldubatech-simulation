/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.physics

import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}


class TravelSpec
	extends WordSpec
		with Matchers
    with WordSpecLike
    with BeforeAndAfterAll
		with LogEnabled {

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
  }

	"Travel Physics" should {
		val underTest = Travel(2 ,10, 6)
		"Cmpute the right trabel time" when {
			"given long distances as rampUP + RampDown + distance/speed" in {
				underTest.travelTime(100) should be (58)
			}
			"given a negative distance" in {
				underTest.travelTime(-100) should be (58)
			}
			"For a distance exactly the rampup+rampDown" in {
				underTest.travelTime(18) should be (17)
			}
			"for Zero should be Zerot" in {
				underTest.travelTime(0) should be (0)
			}
			"For a short distance should be proportional to the ramp up and ramp down times" in {
				underTest.travelTime(4) should be (8)
			}
		}
	}
}
