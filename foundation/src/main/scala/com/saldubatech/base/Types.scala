/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

object Types {
	sealed trait Side
	case object LeftSide extends Side
	case object RightSide extends Side

}
