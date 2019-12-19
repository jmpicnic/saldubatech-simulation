/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.model.configuration

import com.saldubatech.base.Identification
import com.saldubatech.equipment.units.unitsorter.CircularPathPhysics

object Sorting {
	case class CircularSorter(id: String,
	                          inductPoints: Map[String, Int],
	                          dischargePoints: Map[String, Int],
	                          physics: CircularPathPhysics) extends Identification.Impl(id)
}
