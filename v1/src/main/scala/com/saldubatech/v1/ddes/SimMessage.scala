/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.ddes

import com.saldubatech.base.Identification
import com.saldubatech.util.Lang._

object SimMessage {
	class Impl(_id: String) extends Identification.Impl(_id) with SimMessage
}

trait SimMessage extends Identification
