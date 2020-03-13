/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

object Aisle {

	case class LevelLocator(side: Side.Value, idx: Int) {
		override def toString: String = s"$side[$idx]"
	}

	object Side extends Enumeration {
		val LEFT, RIGHT = Value
	}
	case class Locator(level: Int, side: Aisle.Side.Value, slot: Int) {
		val inLevel = Aisle.LevelLocator(side,slot)
	}
}
