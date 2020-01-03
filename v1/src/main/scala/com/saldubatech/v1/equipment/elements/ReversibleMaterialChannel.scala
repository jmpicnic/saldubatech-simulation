/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.elements

import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.v1.ReversibleChannel


class ReversibleMaterialChannel(capacity: Int,
                                _name: String = java.util.UUID.randomUUID().toString)
	extends ReversibleChannel[Material](capacity, _name) {
}
