package com.saldubatech.nodes

import com.saldubatech.protocols.MaterialLoad
import com.saldubatech.test.BaseSpec

import scala.collection.Set

class InventoryManagerSpec extends BaseSpec {
	"An Inventory Manager" when {
		val probes = (0 until 100).map(idx => MaterialLoad(s"ML_$idx"))
		val underTest = new InventoryManager()
		"A. Initialized" should {
			"A01. Be Initially Empty" in {
				underTest.contents should be(Set.empty)
			}
			"A02. Accept inventory provisioning" in {
				underTest.provision(probes: _*)
				underTest.contents.size should be(100)
				underTest.available.contents.size should be(100)
			}
		}
		"B. Provisioned" should {
			var rsv: Option[underTest.Reservation] = None
			"B01. Allow for reservation based on load id's" in {
				rsv = underTest.reserve(probes.head.uid)
				rsv should not be None
				underTest.isValid(rsv.head) should be (true)
				rsv.head.held.contains(probes.head) should be (true)
				rsv.head.held.size should be (1)
				underTest.available.contents.size should be (99)
				underTest.reserved.contents.size should be (1)
			}
			"B02. And then be able to release it back" in {
				underTest.release(rsv.head) should be (None)
				underTest.available.contents.size should be (100)
				underTest.reserved.contents.size should be (0)
				underTest.isValid(rsv.head) should be (false)
			}
		}
		var rsvMany: Option[underTest.Reservation] = None
		"C. it has multiple inventory items" should {
			"C01. Allow for reservation of a part of them" in {
				rsvMany = underTest.reserve(10)
				rsvMany should not be None
				underTest.isValid(rsvMany.head) should be (true)
				rsvMany.head.held.size should be (10)
				underTest.available.contents.size should be (90)
				underTest.reserved.contents.size should be (10)
			}
			"C02. Reject a reservation for more than available items" in {
				underTest.reserve(99) should be (None)
			}
		}
		"D. It consumes a part of the reservation" should {
			"D01. reduce inventory by one" in {
				val rsv3 = underTest.consume(rsvMany.head, rsvMany.head.held.take(3).toSeq: _*)
				rsv3 should not be None
				rsv3.head.held.size should be (7)
				underTest.reserved.contents.size should be (7)
				underTest.available.contents.size should be (90)
				underTest.isValid(rsvMany.head) should be (false)
				underTest.isValid(rsv3.head) should be (true)
				underTest.release(rsv3.head) should be (None)
				underTest.isValid(rsv3.head) should be (false)
				underTest.available.contents.size should be (97)
				underTest.reserved.contents.size should be (0)
			}
		}
	}

}
