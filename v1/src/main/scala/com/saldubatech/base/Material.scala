/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import com.saldubatech.base.resource.Use
import com.saldubatech.util.Lang._

import scala.collection.mutable

object Material {
	def apply(uid: String): Material = {
		new Material(uid)
	}
	def apply(): Material = {
		apply(java.util.UUID.randomUUID.toString)
	}

	class Composite[M <: Material](val contents: List[M],
	                     id: String = java.util.UUID.randomUUID.toString)
		extends Material(id)


	abstract class CompositeBuilder[M <: Material, C <: Composite[M]](val capacity: Option[Int] = None)
	extends Identification.Impl with Use.Usable {
		private val storage: mutable.ListBuffer[M] = mutable.ListBuffer.empty

		def currentContents: List[M] = storage.toList

		protected def newComposite(contents: List[M], id: String): C

		def isIdle: Boolean = storage.isEmpty
		def isBusy: Boolean = capacity.nonEmpty && storage.length == capacity.!
		def isInUse: Boolean = !(isIdle || isBusy)
		def quantity: Int = storage.length


		def maybeAddContent(m: M): Boolean = {
			if(capacity.isEmpty || !isBusy) {
				storage += m
				true
			} else false
		}
		def build(id: String = java.util.UUID.randomUUID.toString): C = {
			val r = newComposite(storage.toList, id)
			storage.clear
			r
		}
	}

	type Tote = Material
	def Tote(id: String) = Material(id)

	case class TotePallet(override val contents: List[Tote], id: String = java.util.UUID.randomUUID.toString) extends Composite[Material](contents, id)

	val DefaultPalletBuilder: (String, List[Tote]) => Option[TotePallet] = (id, contents) => TotePallet(contents, id).?
}

class Material(id: String)
	extends Identification.Impl(id) {
	override def toString: String = uid
}
