/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.physics


object Geography {

	trait Point[P <:Point[P]] {
		def -(other: P): Long
	}

	case class Length(l: Long)
	implicit def lengthToLong(in: Length): Long = in.l

	class GenericLinearPoint[P <: GenericLinearPoint[P]](val coord: Long)
		extends Point[P] {
		private def THE: P = this.asInstanceOf[P]
		override def -(other: P): Long = coord - other.coord
		final def ==(other: P): Boolean = coord == other.coord
		final def !=(other: P): Boolean = !(this == other)

		final def <(other: P): Boolean = other - THE < 0
		final def >(other: P): Boolean = other - THE > 0

		final def <=(other: P): Boolean = !(this > other)
		final def >=(other: P): Boolean = !(this < other)

		override def toString: String = coord.toString
	}
	class LinearPoint(coord: Long) extends GenericLinearPoint[LinearPoint](coord) {
		override def -(other: LinearPoint): Long =
			Math.abs(super.-(other))
	}
	implicit def linearPointBoxer(v: Long): LinearPoint = new LinearPoint(v)
	implicit def linearPointBoxer(v: Int): LinearPoint = new LinearPoint(v)

	class ClosedPathPoint(raw: Long)(implicit circ: Length)
		extends GenericLinearPoint[ClosedPathPoint](raw%circ) {
		override def -(other: ClosedPathPoint): Long = {
			val raw = super.-(other)
			if(raw < 0) raw + circ else raw
		}

	}
	implicit def closedPathPointBoxer(v: Long)(implicit c: Length): ClosedPathPoint =
		new ClosedPathPoint(v)
	implicit def closedPathPointBoxer(v: Int)(implicit c: Length): ClosedPathPoint =
		new ClosedPathPoint(v)


	class EuclideanPlanePoint(val x: Long, val y:Long) extends Point[EuclideanPlanePoint] {
		override def -(other: EuclideanPlanePoint): Long =
			Math.round(Math.sqrt((x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)))
	}
	case class ManhattanPlanePoint(x: Long, y:Long) extends Point[ManhattanPlanePoint] {
		override def -(other: ManhattanPlanePoint): Long = Math.abs(x-other.x)+Math.abs(y-other.y)
	}


	class LinearGeography extends Geography[LinearPoint]
	class EuclideanPlaneGeography
		extends Geography[EuclideanPlanePoint]


	class ClosedPathGeography(pl: Long)
		extends Geography[ClosedPathPoint] {
		implicit val pathLength: Length = Length(pl)
	}

}

trait Geography[P <: Geography.Point[P]] {

	def distance(from: P, to: P): Long = to - from

	def distance(path: Seq[P]): Long =
		path.sliding(2).map {case Seq(p, s, _*) => distance(p, s) }.sum
}
