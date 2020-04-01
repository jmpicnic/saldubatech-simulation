package com.saldubatech.units.carriage

import com.saldubatech.units.lift.BidirectionalCrossSwitch
import com.saldubatech.units.shuttle.Shuttle.ShuttleLevelSignal

trait CarriageNotification
	extends ShuttleLevelSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
