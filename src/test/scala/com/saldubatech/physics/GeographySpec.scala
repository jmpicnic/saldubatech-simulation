/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */



package com.saldubatech.physics

import com.saldubatech.test.utils.BaseSpec


class GeographySpec extends BaseSpec {
	import Geography._

	implicit val nTrays: Length = Length(12)
	var underTest = new Geography.ClosedPathGeography(nTrays)
	"A Circular Geography" when {
		"just initialized" should {
			"keep the pathLength" in {
				underTest.pathLength shouldBe nTrays
			}
			"Compute the direct distance" in {
				underTest.distance(1, 7) shouldBe 6
			}
			"Compute the around distance" in {
				underTest.distance(7, 1) shouldBe 6
				underTest.distance(9, 1) shouldBe 4
				underTest.distance(6, 2) shouldBe 8
			}
		}
	}

}
