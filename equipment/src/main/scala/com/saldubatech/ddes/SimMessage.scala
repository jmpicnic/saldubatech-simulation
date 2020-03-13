/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

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
	class Impl(_id: String) extends Identification.Impl(_id) with SimMessage
}

trait SimMessage extends Identification
