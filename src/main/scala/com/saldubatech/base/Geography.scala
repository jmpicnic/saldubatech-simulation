/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

object Geography {
	trait Tag extends Identification

	trait Point[P <:Point[P]] {
		def -(other: P): Long
	}

	case class LinearPoint(coord: Long) extends Point[LinearPoint] {
		override def -(other: LinearPoint): Long = Math.abs(coord - other.coord)
		def ==(other: LinearPoint): Boolean = coord == other.coord
		def <(other: LinearPoint): Boolean = coord < other.coord
		def >(other: LinearPoint): Boolean = coord > other.coord
		def <=(other: LinearPoint): Boolean = coord <= other.coord
		def >=(other: LinearPoint): Boolean = coord >= other.coord
	}
	implicit def linearPointBoxer(v: Long): LinearPoint = LinearPoint(v)
	implicit def linearPointBoxer(v: Int): LinearPoint = LinearPoint(v)

	case class EuclideanPlanePoint(x: Long, y:Long) extends Point[EuclideanPlanePoint] {
		override def -(other: EuclideanPlanePoint): Long =
			Math.round(Math.sqrt((x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)))
	}

	class LinearReversible[T <: Tag, P <: Point[P]](tags: Map[T, P]) extends Geography[T, P] {
		override def distance(from: T, to: T): Long = Math.abs(tags(from) - tags(to))

		override def contents: Iterable[T] = tags.keys

		override 	def isValid(t: T): Boolean = tags contains t

		override def location(t: T): P = tags(t)
	}

	class LinearGeography[T <: Tag](tags: Map[T, LinearPoint]) extends LinearReversible[T, LinearPoint](tags)
	class EuclideanPlaneGeography[T <: Tag](tags: Map[T, EuclideanPlanePoint]) extends LinearReversible[T, EuclideanPlanePoint](tags)

	case class ManhattanPlanePoint(x: Long, y:Long) extends Point[ManhattanPlanePoint] {
		override def -(other: ManhattanPlanePoint): Long = Math.abs(x-other.x)+Math.abs(y-other.y)
	}

	class ClosedPathGeography[T <: Tag](tags: Map[T, LinearPoint], pathLength: Long) extends LinearGeography[T](tags) {
		override def distance(from: T, to: T): Long = {
			val fromLoc = tags(from)
			val toLoc = tags(to)
			if(fromLoc < toLoc) toLoc - fromLoc else pathLength - (fromLoc - toLoc)
		}
	}

}

trait Geography[T <: Geography.Tag, P <: Geography.Point[P]] {
	import Geography._

	def contents: Iterable[T]

	def location(t: T): P

	def isValid(t: T): Boolean

	def distance(from: T, to: T): Long

	def distance(path: Seq[T]): Long =
		path.sliding(2).map {case Seq(p, s, _*) => distance(p, s) }.sum

	def path(from: T, to: T): Seq[T] = Seq(from, to)

}
