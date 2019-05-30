/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import com.saldubatech.base.resource.Use
import com.saldubatech.utils.Boxer._

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
	extends Use.Usable {
		private val storage: mutable.ListBuffer[M] = mutable.ListBuffer.empty

		def currentContents: List[M] = storage.toList

		protected def newComposite(contents: List[M], id: String): C

		def isEmpty: Boolean = storage.isEmpty
		def isFull: Boolean = capacity.nonEmpty && storage.length == capacity.!
		def isInUse: Boolean = !(isEmpty || isFull)
		def quantity: Int = storage.length


		def maybeAddContent(m: M): Boolean = {
			if(capacity.isEmpty || !isFull) {
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

	class TotePalletBuilder(capacity: Option[Int] = None) extends CompositeBuilder[Tote, TotePallet](capacity) {
		protected def newComposite(contents: List[Tote], id: String) = TotePallet(contents, id)
	}
}

class Material(id: String)
	extends Identification.Impl(id) {
	override def toString: String = uid
}
