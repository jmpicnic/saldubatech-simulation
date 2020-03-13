/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import com.saldubatech.test.BaseSpec

class AssetBoxSpec extends BaseSpec {

	"An Asset Box" when {
		"A. Just Created" should {
			val underTest = new AssetBox[Int]((1 to 10).toSet, "underTest")
			"A0: be full" in {
				underTest.isFull shouldBe true
				underTest.isEmpty shouldBe false
			}
			"A1: Have the same available tokens as provided at creation" in {
				underTest.available shouldBe 10
				(1 to 10).foreach{
					idx => underTest.isAvailable(idx) shouldBe true; log.debug(s"idx: $idx should be available")
				}
			}
		}
		"B: Initialized with 2 resources" should {
			val underTest = new AssetBox[Int]((1 to 2).toSet, "underTest")
			"B1. Checkout an asset when requested" in {
				underTest.checkout(2)  shouldBe Some(2)
				underTest.isFull shouldBe false
				underTest.isEmpty shouldBe false
				underTest.available shouldBe 1
			}
			"B4 Not allow to checkout an asset it does not have" in {
				underTest.checkout(44) shouldBe None
				underTest.isFull shouldBe false
				underTest.isEmpty shouldBe false
				underTest.available shouldBe 1
			}
			"B3 Allow to check the second asset without specifying" in {
				underTest.checkoutAny shouldBe Some(1)
				underTest.isFull shouldBe false
				underTest.isEmpty shouldBe true
				underTest.available shouldBe 0
			}
			"B4 show empty and not allow to checkout any other assets" in {
				underTest.checkoutAny shouldBe None
				underTest.checkout(2) shouldBe None
				underTest.checkout(44) shouldBe None
				underTest.isFull shouldBe false
				underTest.isEmpty shouldBe true
				underTest.available shouldBe 0
			}
		}
		"C: Initialized with 2 resources and one checked out" should {
			val underTest = new AssetBox[Int]((1 to 2).toSet, "underTest")
			underTest.checkout(1) shouldBe Some(1)
			"C1 not change when checkin a different asset" in {
				try {
					underTest.checkin(2)
					fail(s"Should have rejected 2")
				} catch {
					case e: IllegalStateException =>
						underTest.available shouldBe 1
				}
			}
			"C2 Reject an asset that is not checked out" in {
				try {
					underTest.checkin(33)
					fail(s"Should have rejected 33")
				} catch {
					case e: IllegalArgumentException =>
						underTest.available shouldBe 1
				}
			}
			"C3 Allow to checkin the checked out asset" in {
				underTest.checkin(1)
				underTest.isFull shouldBe true
			}
		}
	}

}
