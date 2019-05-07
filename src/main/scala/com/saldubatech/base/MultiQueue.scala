/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import scala.collection.mutable

object MultiQueue {
	def apply[K,V]() = new MultiQueue[K,V]()
}


// @TODO add collection utilities for traversing etc...
class MultiQueue[K,V] {
	private val store: mutable.Map[K, mutable.ListBuffer[V]] = mutable.Map()

	def enqueue(k: K, v: V): Unit = {
		if(!(store contains k)) store.put(k,mutable.ListBuffer())
		store(k) += v
	}

	def dequeue(k: K): Option[V] = {
		if(store contains k) {
			val q = store(k)
			if (q nonEmpty) {
				val r = q.head
				q -= r
				Some(r)
			}
			else
				None
		}  else
			None
	}

	def dequeue(k:K, v:V): Option[V] = {
		if(store contains k) {
			val q = store(k)
			if (q contains v) {
				q -= v
				Some(v)
			} else
				None
		}  else
			None
	}

	def size(k: K): Int = if(store contains k) store(k).size else 0

	def isEmpty: Boolean = store.isEmpty || store.values.forall(p => p.isEmpty)

	def nonEmpty: Boolean = store.nonEmpty

}
