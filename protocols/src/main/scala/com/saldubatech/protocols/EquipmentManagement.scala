package com.saldubatech.protocols

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Simulation.DomainSignal

object EquipmentManagement {
	trait MockManagerSignal extends DomainSignal

	trait ShuttleDemandRequest extends DomainSignal


	trait EquipmentNotification extends DomainSignal
		with MockManagerSignal

	trait XSwitchNotification extends EquipmentNotification
	trait ShuttleNotification extends EquipmentNotification
		with ShuttleDemandRequest

	trait UnitSorterNotification extends EquipmentNotification

}
