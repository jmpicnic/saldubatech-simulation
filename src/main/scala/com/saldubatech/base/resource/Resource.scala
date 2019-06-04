/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.resource

import com.saldubatech.base.Identification
import com.saldubatech.base.resource.Use.Usable

object Resource {
	abstract class Impl(id: String = java.util.UUID.randomUUID.toString) extends Resource {
		override protected def givenId: Option[String] = Some(id)
	}
}

trait Resource extends Usable with Identification
