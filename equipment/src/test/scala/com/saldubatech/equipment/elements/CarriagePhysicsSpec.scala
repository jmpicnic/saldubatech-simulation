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

package com.saldubatech.equipment.elements


import com.saldubatech.base.CarriagePhysics
import com.saldubatech.test.utils.BaseSpec

import scala.languageFeature.postfixOps


class CarriagePhysicsSpec extends BaseSpec {

	"Carriage Physics" when {
		"configured in the constructor" should {
			val acquireTime = 22
			val releaseTime = 33
			val latencyPerPosition = 10
			val accelerationRunway = 10
			val stoppingRunway = 6

			val underTest = CarriagePhysics(acquireTime,
				releaseTime,
				latencyPerPosition,
				accelerationRunway,
				stoppingRunway
			)
			"have the acquireTime and releaseTime as variables" in {
				underTest.acquireTime shouldBe acquireTime
				underTest.releaseTime shouldBe releaseTime
			}
			"compute the accelerationTime, stoppingTime and transientTime for long distances" in {
				underTest.accelerationTime(20) shouldBe 2 * 10 * 10
				underTest.stoppingTime(20) shouldBe 2 * 6 * 10
				underTest.transientTime(20) shouldBe 2 * 16 * 10
			}
			"compute the accelerationTime, stoppingTime and transientTime for short distances" in {
				underTest.accelerationTime(4) shouldBe 2 * 10 * 10 / 2
				underTest.stoppingTime(4) shouldBe 2 * 6 * 10 / 2
				underTest.transientTime(4) shouldBe 2 * 16 * 10 / 2
			}
			"compute the travel time for long and short distances" in {
				underTest.travelTime(20) shouldBe 2 * 16 * 10 + 40
				underTest.travelTime(4) shouldBe 2 * 16 * 10 / 2
			}
			"compute the timeToStage for long and short distances" in {
				underTest.timeToStage(20-40) shouldBe acquireTime + 2 * 16 * 10 + 40
				underTest.timeToStage(6-10) shouldBe acquireTime + 2 * 16 * 10 / 2
			}
			"compute the timeToDeliver for long and short distances" in {
				underTest.timeToStage(20-40) shouldBe acquireTime + 2 * 16 * 10 + 40
				underTest.timeToStage(6-10) shouldBe acquireTime + 2 * 16 * 10 / 2
			}
		}
	}
}
