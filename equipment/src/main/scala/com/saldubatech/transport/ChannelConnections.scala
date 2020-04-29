/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.units.shuttle.{LoadAwareShuttle, Shuttle}
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.units.unitsorter.UnitSorterSignal

object ChannelConnections {
	// Registration of consumers of the messages to allow the typing of Actors to work O.K.
	trait DummySourceMessageType
	trait DummySinkMessageType
	trait DummyChannelMessageType

	trait ChannelSourceSink extends DummyChannelMessageType
		with Shuttle.ShuttleSignal
		with XSwitch.XSwitchSignal
		with UnitSorterSignal
		with LoadAwareShuttle.LoadShuttleSignal



	trait ChannelSourceMessage extends ChannelSourceSink with DummySourceMessageType

	trait ChannelDestinationMessage extends ChannelSourceSink with DummySinkMessageType

}
