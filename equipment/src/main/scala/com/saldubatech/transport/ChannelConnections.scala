/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.units.unitsorter.UnitSorterSignal

object ChannelConnections {
	// Registration of consumers of the messages to allow the typing of Actors to work O.K.
	trait DummySourceMessageType
	trait DummySinkMessageType
	trait DummyChannelMessageType

	trait ChannelSourceMessage extends DummySourceMessageType with DummyChannelMessageType
		with Shuttle.ShuttleSignal
		with XSwitch.XSwitchSignal
		with UnitSorterSignal

	trait ChannelDestinationMessage extends DummySinkMessageType with DummyChannelMessageType
		with Shuttle.ShuttleSignal
		with XSwitch.XSwitchSignal
		with UnitSorterSignal

}
