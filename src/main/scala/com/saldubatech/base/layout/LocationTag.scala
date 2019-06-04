/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.layout

import com.saldubatech.base.Identification
import com.saldubatech.utils.Boxer._

object LocationTag {
	abstract class Impl(id: String) extends LocationTag {
		override protected def givenId: Option[String] = id.?
	}
}

trait LocationTag extends Identification
