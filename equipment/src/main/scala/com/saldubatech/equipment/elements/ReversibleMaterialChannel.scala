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

package com.saldubatech.equipment.elements

import com.saldubatech.base.Material
import com.saldubatech.base.channels.v1.ReversibleChannel


class ReversibleMaterialChannel(capacity: Int,
                                _name: String = java.util.UUID.randomUUID().toString)
	extends ReversibleChannel[Material](capacity, _name) {
}
