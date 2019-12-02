/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.util

object Implicits {
	implicit def stringUUID(uuid: java.util.UUID): String = uuid.toString

}
