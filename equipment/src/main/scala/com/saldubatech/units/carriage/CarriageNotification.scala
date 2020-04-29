package com.saldubatech.units.carriage

import com.saldubatech.units.abstractions.EquipmentManager
import com.saldubatech.units.shuttle.Shuttle

trait CarriageNotification
	extends EquipmentManager.Notification
		with Shuttle.ShuttleSignal
