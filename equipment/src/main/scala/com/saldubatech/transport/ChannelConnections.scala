/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.units.shuttle.ShuttleLevel

object ChannelConnections {
	// Registration of consumers of the messages to allow the typing of Actors to work O.K.
	trait ChannelSourceMessage extends ShuttleLevel.ShuttleLevelMessage
	trait ChannelDestinationMessage extends ShuttleLevel.ShuttleLevelMessage
}
