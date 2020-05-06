package com.saldubatech.protocols

import com.saldubatech.base.Identification

object Equipment {
	trait ShuttleSignal extends Identification
	trait XSwitchSignal extends Identification
	trait UnitSorterSignal extends Identification

	trait MockSignal extends Identification
	trait MockSourceSignal extends Identification
	trait MockSinkSignal extends Identification

	trait ChannelSignal extends Identification
		with MockSignal
		with ShuttleSignal
		with XSwitchSignal
		with UnitSorterSignal

	trait ChannelSourceSignal extends ChannelSignal
		with MockSourceSignal
	trait ChannelSinkSignal extends ChannelSignal
		with MockSinkSignal

}
