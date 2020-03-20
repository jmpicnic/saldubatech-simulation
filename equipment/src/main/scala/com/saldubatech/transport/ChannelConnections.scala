/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.lift.{FanIn, BidirectionalCrossSwitch}

object ChannelConnections {
	// Registration of consumers of the messages to allow the typing of Actors to work O.K.
	trait DummySourceMessageType
	trait DummySinkMessageType

	trait ChannelSourceMessage extends DummySourceMessageType
		with Shuttle.ShuttleLevelSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
		with FanIn.LiftAssemblySignal
	trait ChannelDestinationMessage extends DummySinkMessageType
		with Shuttle.ShuttleLevelSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
		with FanIn.LiftAssemblySignal

}
