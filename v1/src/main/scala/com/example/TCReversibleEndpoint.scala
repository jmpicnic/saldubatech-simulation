/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.example

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.Material

/**
	* THis is not for production, but just to learn a bit about typeclasses and extending classes with "ad-hoc" polymorphism.
	*
	* @see https://blog.scalac.io/2017/04/19/typeclasses-in-scala.html
	*/
object TCReversibleEndpoint{
	implicit val loadSender: TCReversibleEndpoint[Material] = new TCReversibleEndpoint[Material] {
		def sendLoad(load: Material, at: Long): Boolean = true
		def doSomething(s: String): Unit = {}
	}

	def sendLoad[L <: Identification](load: L, at: Long)(implicit rev: TCReversibleEndpoint[L]): Boolean = rev.sendLoad(load, at)
	def sendLoad2[L <: Identification : TCReversibleEndpoint](load: L, at: Long)(l: L): Boolean = implicitly[TCReversibleEndpoint[L]].sendLoad(l, at)

}

trait TCReversibleEndpoint[L <: Identification] {
	def sendLoad(load: L, at: Long): Boolean
	def doSomething(s: String): Unit
}
