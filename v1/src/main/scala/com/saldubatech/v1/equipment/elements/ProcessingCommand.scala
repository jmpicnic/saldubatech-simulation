/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.elements

import com.saldubatech.base.Identification
import com.saldubatech.util.Lang._

object ProcessingCommand{
	def apply(): ProcessingCommand = new Impl()
	def apply(id: String): ProcessingCommand = new Impl(id)

	class Impl(id: String = java.util.UUID.randomUUID().toString) extends ProcessingCommand {
		override def givenId: Option[String] = id.?
	}

}

trait ProcessingCommand extends Identification
