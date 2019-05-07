/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

object Material {
	def apply(uid: String): Material = {
		new Material(uid)
	}
	def apply(): Material = {
		apply(java.util.UUID.randomUUID.toString)
	}
}

class Material(val id: String)
	extends Identification {

	override protected def givenId: Option[String] = Some(id)

	override def toString: String = uid

}
