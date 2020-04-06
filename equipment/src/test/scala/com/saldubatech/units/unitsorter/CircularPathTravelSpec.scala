/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.unitsorter

import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}


class CircularPathTravelSpec
	extends WordSpec
		with Matchers
    with WordSpecLike
    with BeforeAndAfterAll
		with LogEnabled {

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
  }

	"A CircularPathTravel" when {
		val underTest = CircularPathTravel(60, 25, 100)
		"Initialized" should {
			"compute the value of a complete turn" in {
				underTest.oneTurnTime should be (240L)
			}
			"compute the value for positions ahead" in {
				underTest.travelTime(3, 7) should be (16L)
			}
			"compute the value for positions behind" in {
				underTest.travelTime(58, 2) should be (16L)
			}
		}
	}
}
