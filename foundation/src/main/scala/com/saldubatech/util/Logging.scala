/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.util

import com.typesafe.scalalogging.Logger

trait Logging {
	protected val log = Logger(this.getClass.getName)

}
