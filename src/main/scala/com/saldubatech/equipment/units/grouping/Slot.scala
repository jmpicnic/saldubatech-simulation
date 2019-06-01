/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.equipment.units.grouping

import com.saldubatech.base.Material
import com.saldubatech.base.Material.Composite
import com.saldubatech.base.processor.Task.ExecutionResourceImpl
import com.saldubatech.base.resource.Use
import com.saldubatech.utils.Boxer._

import scala.collection.mutable

object Slot {
	def apply[M <: Material, PR <: Composite[M]](id: String, capacity: Option[Int] = None)
	                                            (implicit slot: Material.CompositeBuilder[M, PR]): Slot[M, PR]
	= new Slot(id, capacity)


	type Deposit[M <: Material] = {val tk: Option[String]; val material: M}
	def Deposit[M <: Material](token: Option[String], mat: M): Deposit[M] = {object r {val tk = token; val material = mat}; r}
}


// This can be made a bit more sophisticated in terms of reservations
class Slot[M <: Material, PR <: Composite[M]](val id: String, capacity: Option[Int] = None)(implicit val slot: Material.CompositeBuilder[M, PR])
	extends ExecutionResourceImpl(id) with Use.Usable {
	import Slot._

	override def hashCode: Int = id.hashCode

	override def equals(obj: Any): Boolean = obj match {
		case s: Slot[M, PR] => id == s.id
		case _ => false
	}


	def isIdle:Boolean = usage == 0
	def isBusy: Boolean = capacity.isDefined && usage == capacity
	def isInUse: Boolean = usage > 0
	def usage: Int = slot.quantity + reserved.size

	private var serial = 0
	private var reserved: mutable.Set[String] = mutable.Set.empty

	def reserve: Option[String] =
		if(isBusy) None
		else {
			val tk = java.util.UUID.randomUUID.toString
			reserved += tk
			tk.?
		}
	def release(tk: String): Boolean =
		if(isReserved(tk)) {
			reserved -= tk
			true
		} else {
			false
		}
	def isReserved(tk: String): Boolean =
		reserved contains tk

	def <<(deposit: Deposit[M]): Boolean =
		if(deposit.tk.isEmpty && !isBusy)
			slot.maybeAddContent(deposit.material)
		else if(deposit.tk.nonEmpty && isReserved(deposit.tk.!)) {
			assert(slot.maybeAddContent(deposit.material), "if reserved, it should have space")
			release(deposit.tk.!)
		} else false

	def build: Option[PR] = {
		slot.build(f"$id+$serial%05d").?
	}
}
