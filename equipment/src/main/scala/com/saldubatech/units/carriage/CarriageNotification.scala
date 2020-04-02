package com.saldubatech.units.carriage

import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.lift.BidirectionalCrossSwitch
import com.saldubatech.units.shuttle.Shuttle.ShuttleSignal

trait CarriageNotification
	extends EquipmentManager.Notification
		with ShuttleSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
