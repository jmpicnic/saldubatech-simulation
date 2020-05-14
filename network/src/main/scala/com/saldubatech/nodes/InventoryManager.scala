package com.saldubatech.nodes

import com.saldubatech.base.Identification
import com.saldubatech.protocols.MaterialLoad
import com.sun.tools.javac.jvm.Items

import scala.collection.mutable

object InventoryManager {

	sealed trait Inventory {
		def contains(ld: MaterialLoad): Boolean
		def filter(f: MaterialLoad => Boolean): Set[MaterialLoad]
		def find(f: MaterialLoad => Boolean): Option[MaterialLoad]
		def contents: Set[MaterialLoad]
		def get(id: String): Option[MaterialLoad]
	}

	private trait Entry {
		val item: MaterialLoad
	}
	private case class Free(override val item: MaterialLoad) extends Entry
	private case class Reserved(override val item: MaterialLoad) extends Entry

}

class InventoryManager extends Identification.Impl() with InventoryManager.Inventory {
	import InventoryManager._

	override def contains(ld: MaterialLoad): Boolean = inventory.contains(ld.uid)
	override def filter(f: MaterialLoad => Boolean): Set[MaterialLoad] = inventory.values.filter(entry => f(entry.item)).map(_.item).toSet
	override def find(f: MaterialLoad => Boolean): Option[MaterialLoad] = inventory.values.find(entry => f(entry.item)).map(_.item)
	override def contents: Set[MaterialLoad] = inventory.values.map(_.item).toSet
	override def get(id: String): Option[MaterialLoad] = inventory.get(id).map(_.item)

	private class Inspector(cnd: Entry => Boolean) extends Inventory {
		override def contains(ld: MaterialLoad) = InventoryManager.this.inventory.filter(t => cnd(t._2)).contains(ld.uid)
		override def filter(f: MaterialLoad => Boolean) = inventory.values.filter(entry => cnd(entry) && f(entry.item)).map(_.item).toSet
		override def find(f: MaterialLoad => Boolean) = inventory.values.find(entry => cnd(entry) && f(entry.item)).map(_.item)
		override def contents = inventory.values.filter(entry => cnd(entry)).map(_.item).toSet
		override def get(id: String) = inventory.get(id).map(_.item)
	}

	sealed trait Reservation extends Identification {
		val held: Set[MaterialLoad]
	}

	private val inventory: mutable.Map[String, Entry] = mutable.Map.empty
	private val reservations: mutable.Map[String, Reservation] = mutable.Map.empty

	private val isFree: PartialFunction[Entry, Boolean] = {
		case Free(_) => true
		case Reserved(_) => false
	}
	private val isReserved: PartialFunction[Entry, Boolean] = {case e => !isFree(e)}

	// Provisioning
	def provision(items: MaterialLoad*): Unit = inventory ++= items.map(ld => ld.uid -> Free(ld))

	// Inspection
	def available: Inventory = new Inspector(isFree)
	def reserved: Inventory = new Inspector(isReserved)
	def withCondition(cnd: MaterialLoad => Boolean): Inventory = new Inspector(entry => cnd(entry.item))

	// Reserving
	private case class ReservationImpl(override val held: Set[MaterialLoad]) extends Identification.Impl() with Reservation

	def reserve(count: Int): Option[Reservation] =
		if(count > available.contents.size) None
		else {
			doReserve(inventory.filter(e => isFree(e._2)).take(count).map{e =>
				inventory += e._1 -> Reserved(e._2.item)
				e._2.item
			})
		}

	def reserve(id: String*): Option[Reservation] = reserve(l => id.contains(l.uid))
	def reserve(cnd: MaterialLoad => Boolean): Option[Reservation] =
		doReserve(inventory.filter(e => cnd(e._2.item) && isFree(e._2)).map{e =>
			inventory += e._1 -> Reserved(e._2.item)
			e._2.item
		})

	private def doReserve(candidates: Iterable[MaterialLoad]): Option[Reservation] =
		if(candidates nonEmpty) {
			val rsv = ReservationImpl(candidates.toSet)
			reservations += rsv.uid -> rsv
			Some(rsv)
		}
		else None

	private def releaser(from: Reservation): Option[(MaterialLoad => Boolean) => Option[Reservation]] = reservations.get(from.uid).map { fr =>
		(cnd: MaterialLoad => Boolean) => {
			val (released, retained) = from.held.partition(cnd)
			released.foreach(ld => inventory += ld.uid -> Free(ld))
			reservations -= from.uid
			if (retained isEmpty) None
			else {
				val r = ReservationImpl(retained)
				reservations += r.uid -> r
				Some(r)
			}
		}
	}
	def release(from: Reservation, id: String*) = releaser(from).flatMap(f => f(ld => id.contains(ld.uid)))
	def release(from: Reservation): Option[Reservation] = releaser(from).flatMap(_.apply(_ => true))

	private def consumer(from: Reservation): Option[(MaterialLoad => Boolean) => Option[Reservation]] = reservations.get(from.uid).map(_ =>
			(cnd: MaterialLoad => Boolean) => {
				val (consumed, retained) = from.held.partition(cnd)
				consumed.foreach(ld => inventory -= ld.uid)
				reservations -= from.uid
				if(retained isEmpty) None
				else {
					val r = ReservationImpl(retained)
					reservations += r.uid -> r
					Some(r)
				}
			})
	def consume(from: Reservation, loads: MaterialLoad*): Option[Reservation] = consumer(from).flatMap(f => f(ld => loads.contains(ld)))
	def consume(from: Reservation) = consumer(from).flatMap(_.apply(_ => true))

	def isValid(reservation: Reservation): Boolean = reservations.contains(reservation.uid)

}
