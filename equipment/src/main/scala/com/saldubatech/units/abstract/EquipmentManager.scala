package com.saldubatech.units.`abstract`

import com.saldubatech.base.Identification

object EquipmentManager {
	trait ManagerSignal
	trait Notification extends Identification with ManagerSignal
}

trait EquipmentManager {

}
