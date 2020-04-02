/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.elements

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.Material

object PhysicalJob {
	def apply[C <: Identification](load: Option[Material], command: C):PhysicalJob[C] = new PhysicalJob[C](load, command)
}

class PhysicalJob[C <: Identification](_load: Option[Material], val command: C) {
	val uid: String = java.util.UUID.randomUUID().toString
	val load: Option[Material] = _load

	override def equals(obj: Any): Boolean = {
    obj != null && obj.isInstanceOf[PhysicalJob[C]] && obj.asInstanceOf[PhysicalJob[C]].uid == this.uid
  }

  override def hashCode: Int = uid.hashCode

  override def toString: String =
    super[Object].toString + s"//Id:<$uid> with material: $load and command; $command"

}
