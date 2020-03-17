package com.saldubatech.units.carriage

import com.saldubatech.units.lift.FanIn.LiftAssemblySignal
import com.saldubatech.units.shuttle.ShuttleLevel.ShuttleLevelSignal

trait CarriageNotification
	extends ShuttleLevelSignal
		with LiftAssemblySignal
