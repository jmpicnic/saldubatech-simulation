package com.saldubatech.protocols

import com.saldubatech.base.Identification

object EquipmentManagement {
	trait MockManagerSignal extends Identification

	trait ShuttleDemandRequest extends Identification


	trait EquipmentNotification extends MockManagerSignal

	trait XSwitchNotification extends EquipmentNotification
	trait ShuttleNotification extends EquipmentNotification
		with ShuttleDemandRequest

	trait UnitSorterNotification extends EquipmentNotification

}
