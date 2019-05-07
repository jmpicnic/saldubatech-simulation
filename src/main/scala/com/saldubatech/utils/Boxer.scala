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

		// Sample usage
		val t: Boolean = true
		val o: Option[Boolean] = t ?
		val r: Boolean = o !

	}

}