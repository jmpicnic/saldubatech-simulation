package com.saldubatech.protocols

import com.saldubatech.ddes.Simulation.DomainSignal
import com.saldubatech.protocols.NodeProtocols.ShuttleNodeSignal

object EquipmentManagement {
	trait MockManagerSignal extends DomainSignal


	trait EquipmentNotification extends DomainSignal
		with MockManagerSignal

	trait XSwitchNotification extends EquipmentNotification
	trait ShuttleNotification extends EquipmentNotification
		with ShuttleNodeSignal

	trait UnitSorterNotification extends EquipmentNotification

}
