/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.base.resource

import com.saldubatech.base.Material
import com.saldubatech.base.resource.Use.Usable
import com.saldubatech.util.Lang._

import scala.collection.mutable

object KittingSlot {
	def apply[M <: Material, PR<:Material.Composite[M]](capacity: Option[Int], builder: (String, List[M]) => Option[PR],
                           id: String = java.util.UUID.randomUUID.toString)
	: KittingSlot[M, PR] = new KittingSlot(capacity, builder, id)
}

class KittingSlot[M <: Material, PR <: Material.Composite[M]](capacity: Option[Int],
                                                              builder: (String, List[M]) => Option[PR],
                                                              val id: String= java.util.UUID.randomUUID.toString)
	extends Usable with Resource {
	override protected def givenId: Option[String] = id.?

	private val components: mutable.ListBuffer[M] = mutable.ListBuffer.empty
	private var product: Option[PR] = None

	override def isIdle: Boolean = components.isEmpty
	override def isBusy: Boolean = components.contains(capacity)
	override def isInUse: Boolean = !(isIdle || isBusy)


	def currentContents: List[M] = components.toList
	def currentProduct: Option[PR] = product

	def build: Boolean = build(uid)

	def build(id: String): Boolean =
		if(components isEmpty) false
		else {
			product = builder(id, currentContents)
			components.clear
			true
		}


	def <<(contents: M): Boolean =
		if(isBusy || product.nonEmpty) false
		else {
			components += contents
			true
		}

	def >> : Option[PR] = {
		val r = product
		product = None
		r
	}
}
