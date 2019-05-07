/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import com.saldubatech.base.Identification

import com.saldubatech.utils.Boxer._

object SimMessage {
	class Impl(_id: String) extends SimMessage {
		override def givenId: Option[String] = _id.?
	}
}

trait SimMessage extends Identification
