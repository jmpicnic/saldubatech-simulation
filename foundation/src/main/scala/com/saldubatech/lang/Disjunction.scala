package com.saldubatech.lang

object Disjunction {
	type ~[T] = T => Nothing
	type ~~[T] = ~[~[T]]

	type v[T, U] = ~[~[T] with ~[U]]

	type ||[T, U] = { type OR[X] = ~~[X] <:< v[T, U] }


	// TEST
	def size[T: (Int || String)#OR](t: T) =
		t match {
			case i: Int => i
			case s: String => s.length
		}

	// Another
	type T2[X] = (Int || String)#OR[X]
	type T3[X] = (Int || String)#OR[X]

	def size2[T: T2](t: T) =
		t match {
			case i: Int => i
			case s: String => s.length
		}

	def inject[T: T2](t: T): T = t

	size2(33)
	size2("asdf")
	// Wont compile size(33.0)

}
