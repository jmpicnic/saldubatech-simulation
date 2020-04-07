/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.lift.BidirectionalCrossSwitch
import com.saldubatech.units.unitsorter.UnitSorterSignal

object ChannelConnections {
	// Registration of consumers of the messages to allow the typing of Actors to work O.K.
	trait DummySourceMessageType
	trait DummySinkMessageType

	trait ChannelSourceMessage extends DummySourceMessageType
		with Shuttle.ShuttleSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
		with UnitSorterSignal

	trait ChannelDestinationMessage extends DummySinkMessageType
		with Shuttle.ShuttleSignal
		with BidirectionalCrossSwitch.CrossSwitchSignal
		with UnitSorterSignal

}
