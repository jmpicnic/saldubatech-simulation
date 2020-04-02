/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.base.layout

import com.saldubatech.base.Identification
import com.saldubatech.util.Lang._

object LocationTag {
	abstract class Impl(id: String) extends LocationTag {
		override protected def givenId: Option[String] = id.?
	}
}

trait LocationTag extends Identification
