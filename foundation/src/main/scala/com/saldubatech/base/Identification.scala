/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import com.saldubatech.util.Lang._

object Identification {
	class Impl(id: String = java.util.UUID.randomUUID().toString) extends Identification {
		override def givenId: Option[String] = id.?

		//override def toString: String = uid
	}
}

trait Identification {
	protected def givenId: Option[String]
	lazy val uid: String = if(givenId isDefined) givenId.get else java.util.UUID.randomUUID().toString
}
