/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.physics

import com.saldubatech.base.Identification
import Geography.Point

object TaggedGeography {
	trait Tag extends Identification

	class Impl[T <: Tag, P <: Point[P]](tags: Map[T, P], geo: Geography[P])
	extends TaggedGeography[T, P]{
		override def distance(from: T, to: T): Long = {
			assert(isValid(from) && isValid(to), "Tags must belong to this geography")
			geo.distance(tags(from), tags(to))
		}
		override def distance(from: T, to: P): Long = {
			assert(isValid(from), "Tags must belong to this geography")
			geo.distance(tags(from), to)
		}

		override def distance(from: P, to: T): Long = {
			assert(isValid(to), "Tags must belong to this geography")
			geo.distance(from, tags(to))
		}

		override def contents: Iterable[T] = tags.keys

		override 	def isValid(t: T): Boolean = tags contains t

		override def location(t: T): P = tags(t)
	}
}

trait TaggedGeography[T <: TaggedGeography.Tag, P <: Point[P]] {

	def contents: Iterable[T]

	def location(t: T): P

	def isValid(t: T): Boolean

	def distance(from: T, to: T): Long

	def distance(from: T, to: P): Long

	def distance(from: P, to: T): Long

	def distance(path: Seq[T]): Long =
		path.sliding(2).map {case Seq(p, s, _*) => distance(p, s) }.sum



}
