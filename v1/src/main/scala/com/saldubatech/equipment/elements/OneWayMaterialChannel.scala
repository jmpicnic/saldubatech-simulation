/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import com.saldubatech.base.channels.v1.AbstractChannel.FlowDirection
import com.saldubatech.base.Material
import com.saldubatech.base.channels.v1.OneWayChannel
import com.saldubatech.base.resource.DiscreteResourceBox

object OneWayMaterialChannel {
	def apply(capacity: Int, name:String = java.util.UUID.randomUUID().toString) =
		new OneWayMaterialChannel(capacity, name)

	trait Destination extends OneWayChannel.Destination[Material]

	class Endpoint(name: String, side: FlowDirection.Value, sendingResources: DiscreteResourceBox, receivingResources: DiscreteResourceBox)
		extends OneWayChannel.Endpoint[Material](name, side, sendingResources, receivingResources)
}


class OneWayMaterialChannel(capacity: Int, name:String = java.util.UUID.randomUUID().toString)
	extends OneWayChannel[Material](capacity, name)
