/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.model.configuration

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.Aisle.LevelLocator
import com.saldubatech.v1.base.{CarriagePhysics, Material}
import com.saldubatech.v1.model.configuration.Layout.TransportLink

object ShuttleStorage {
	case class ShuttleLevel(id: String, aisleLength: Int, physics: CarriagePhysics, initialPosition: LevelLocator
	                        , inbound: TransportLink, outbound: TransportLink) extends Identification.Impl(id)

	case class Lift(id: String, physics: CarriagePhysics, ioLevel: Int) extends Identification.Impl(id)

	case class ShuttleAisle(id: String, levels: List[ShuttleLevel], lift: Lift) extends Identification.Impl(id)

	case class ShuttleStore(id: String, aisles: List[ShuttleAisle]) extends  Identification.Impl(id)
}
