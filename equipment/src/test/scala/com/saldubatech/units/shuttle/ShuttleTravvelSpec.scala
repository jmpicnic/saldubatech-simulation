/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor._
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.concurrent.duration._


class ShuttleTravvelSpec
	extends WordSpec
		with Matchers
    with WordSpecLike
    with BeforeAndAfterAll
		with LogEnabled {

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
  }

	"A Shuttle Travel" when {
		val underTest = Shuttle.ShuttleTravel(2,10, 6,7, 5)
		"A. Keep its configuration" should {
			"A01. acquire value" in {
				underTest.acquireTime should be(7L)
			}
			"A02 releaseTime" in {
				underTest.releaseTime should be(5L)
			}
		}
		"B. Cmpute the right trabel time for long distances" when {
			"B01 as rampUP + RampDown + distance/speed" in {
				underTest.travelTime(0, 100) should be (8+50)
			}
			"B02 Long reverse distance" in {
				underTest.travelTime(100, 0) should be (8+50)
			}
			"B03 Long from a negative position" in {
				underTest.travelTime(-100, 0) should be (8+50)
			}
			"B04 Long time across Zero" in {
				underTest.travelTime(-50, 50) should be (8+50)
			}
		}
		"C. Compute the right trabel time for short distances" when {
			"C01 direct forward" in {
				underTest.travelTime(0, 4) should be (8)
			}
			"C02 reverse distance" in {
				underTest.travelTime(4, 0) should be (8)
			}
			"C03 Long from a negative position" in {
				underTest.travelTime(-4, 0) should be (8)
			}
			"C04 Long time across Zero" in {
				underTest.travelTime(-2, 2) should be (8)
			}
		}
	}
}
