package com.saldubatech.units.carriage

import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.shuttle.Shuttle

trait CarriageNotification
	extends EquipmentManager.Notification
		with Shuttle.ShuttleSignal
