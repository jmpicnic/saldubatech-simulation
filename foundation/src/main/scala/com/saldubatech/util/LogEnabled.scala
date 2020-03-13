/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.util

import com.typesafe.scalalogging.Logger

trait LogEnabled {
	protected val logName = this.getClass.getName

	protected val log = Logger(logName)

}
