/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.composites

import com.saldubatech.v1.base.Aisle.Locator
import com.saldubatech.v1.base.{Aisle, Material}
import com.saldubatech.v1.base.processor.Task.ExecutionCommandImpl

object StorageModule {
	class StorageAisleCommand(val starterLevel: Int, val finishLevel: Int, name: String = java.util.UUID.randomUUID.toString)
		extends ExecutionCommandImpl(name)

	case class Inbound(to: Aisle.Locator, loadId: Option[String]) extends StorageAisleCommand(to.level, to.level)
	case class Outbound(from: Aisle.Locator) extends StorageAisleCommand(from.level, from.level)
	case class Groom(from: Aisle.Locator, to: Aisle.Locator) extends StorageAisleCommand(from.level, to.level)

	case class InitializeInventory(inv: Map[Locator, Material])


}
