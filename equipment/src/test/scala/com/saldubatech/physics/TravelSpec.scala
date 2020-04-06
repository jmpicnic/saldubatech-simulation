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

	"A Travel Object" when {
		val underTest = Travel(2,10, 6)
		"B. Cmpute the right trabel time for long distances" when {
			"B01 as rampUP + RampDown + distance/speed" in {
				underTest.travelTime(100) should be (8+50)
			}
			"B02 Long reverse distance" in {
				underTest.travelTime(-100) should be (8+50)
			}
		}
		"C. Compute the right travel time for short distances" when {
			"C01 direct forward" in {
				underTest.travelTime(4) should be (8)
			}
			"C02 reverse distance" in {
				underTest.travelTime(-4) should be (8)
			}
		}
	}
}
