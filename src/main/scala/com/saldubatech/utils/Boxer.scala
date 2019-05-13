/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.utils {

	object Boxer {

		class Boxable[T](target: T) {
			def ?(): Option[T] = Some(target)
		}

		class Deboxable[T](target: Option[T]) {
			def !(): T = target.get
		}

		implicit def boxing[T](c: T): Boxable[T] = new Boxable(c)

		implicit def unboxing[T](o: Option[T]): Deboxable[T] = new Deboxable(o)

		def safeApply[T, R](fu: (T, T) => R)(x: Option[T], y: Option[T]): Option[R] =
			if(x.isDefined && y.isDefined) Some(fu(x.head,y.head)) else None

		def safely[T, R](f: T => R): Function1[Option[T], Option[R]] =
			(x: Option[T]) => if(x isDefined) Some(f(x.head)) else None

		def safely[T, R](f: (T, T) => R): Function2[Option[T], Option[T], Option[R]] =
			(x: Option[T], y: Option[T]) => safeApply(f)(x, y)

		implicit class Safeable1[T, R](fu: T => R) {
			def ?< : Option[T] => Option[R] = safely(fu)
		}
		implicit class Safeable2[T, R](fu: Function2[T, T, R]) {
			def ? : (Option[T], Option[T]) => Option[R] = 	safely(fu)
		}

		def exceptionEater[T, R](f: (T, T) => R) : (T, T) => Option[R] =
			(x: T, y: T) =>
				try Some(f(x, y))
				catch {case e: Any => None}

		// Sample usage
		protected val t: Boolean = true
		val o: Option[Boolean] = t ?
		val r: Boolean = o !
		def f1(a: Int, b: Int): Boolean = a > b
		val f2: (Int, Int) => Boolean = (x, y) => x > y
		val f3 = f1 _

		val mayBeF: Option[Boolean] = safeApply(f1)(Some(3), Some(5))

		val safeFunct: (Option[Int], Option[Int]) => Option[Boolean] = safely(f1 _)

		val ob: Option[Boolean] = f3 ? (Some(3), Some(5))
		val opt1: Option[Boolean] = safeFunct(Some(33), Some(22))
		val opt2: Option[Boolean] = safely(f1 _)(Some(33), Some(44))

		new Safeable2(f1) ? (Some(3), Some(5))

		val ob3: Option[Boolean] = f1 _? (Some(3), Some(5))

		val ee: (Int, Int) => Option[Boolean] = exceptionEater(f1)
		val maybeB: Option[Boolean] = ee(33, 44)

		// Other interesting things


	}

}