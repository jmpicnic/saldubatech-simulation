/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.resource

import scala.collection.mutable


import com.saldubatech.utils.Boxer._

object Use {
	object Use extends Enumeration {
		val IDLE, inUSE, BUSY, UNKNOWN = Value
	}

	trait Usable {
		def isEmpty: Boolean
		def isFull: Boolean
		def isInUse: Boolean
		def useState: Use.Value =
			if(isEmpty) Use.IDLE
			else if (isFull) Use.BUSY
			else if(isInUse) Use.inUSE
			else Use.UNKNOWN
	}

	type Usage[I] = {val tk: Option[String]; val item: I}
	def Usage[I](token: Option[String], itm: I): Usage[I] = {object r {val tk: Option[String] = token; val item: I = itm}; r}

	trait DiscreteUsable[I] extends Usable {
		val items: Map[String, I]
		private var reserved: mutable.Map[String, I] = mutable.Map.empty
		private val inUse: mutable.Map[String, I] = mutable.Map.empty
		private lazy val idle: mutable.Map[String, I] = mutable.Map[String, I](items.toSeq: _*)

		def isEmpty: Boolean = reserved.isEmpty && inUse.isEmpty
		def isFull: Boolean = idle.isEmpty //items.size == reserved.size + inUse.size
		def isInUse: Boolean = !(isEmpty || isFull)

		def isReserved(tk: String): Boolean =
			reserved contains tk

		def reserve(tk: String): Option[String] =
			if(isFull || !idle.contains(tk)) None
			else {
				val item = tk -> idle(tk)
				reserved += item
				idle -= tk
				tk.?
			}

		def reserve: Option[String] =
			if (isFull) None
			else reserve(idle.head._1)

		def abandon(tk: String): Boolean =
			if (isReserved(tk)) {
				idle += tk -> reserved(tk)
				reserved -= tk
				true
			} else false

		def release(usage: Usage[I]): Boolean =
			if (usage.tk isDefined) {
				val key = usage.tk.!
				if (!inUse.contains(key)) false
				else {
					idle += key -> usage.item
					inUse -= key
					true
				}
			} else {
				val found: Option[(String, I)] = inUse.find {
					case (key, item) => item == usage.item
				}
				if (found isEmpty) false
				else {
					inUse -= found.head._1
					idle += found.head
					true
				}
			}


		def acquire: Option[Usage[I]] = acquire(None)
		def acquire(tk: Option[String]): Option[Usage[I]] = {
			if (isFull) None
			else if(tk isEmpty) {
				val (key, itm) = idle.head
				idle -= key
				inUse += key -> itm
				Usage(key.?, itm).?
			} else if(isReserved(tk.!)) {
				val itm = reserved(tk.!)
				reserved -= tk.!
				inUse += tk.! -> itm
				Usage(tk, itm).?
			} else if(idle contains tk.!) {
				val itm = idle(tk.!)
				idle -= tk.!
				inUse += tk.! -> itm
				Usage(tk, itm).?
			}
			else None
		}
	}


}