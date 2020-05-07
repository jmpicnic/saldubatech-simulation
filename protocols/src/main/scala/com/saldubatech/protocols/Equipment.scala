package com.saldubatech.protocols

import com.saldubatech.ddes.Simulation.DomainSignal

object Equipment {
	trait ShuttleSignal extends DomainSignal
	trait XSwitchSignal extends DomainSignal
	trait UnitSorterSignal extends DomainSignal

	trait MockSignal extends DomainSignal
	trait MockSourceSignal extends DomainSignal
	trait MockSinkSignal extends DomainSignal

	trait ChannelSignal extends DomainSignal
		with MockSignal
		with ShuttleSignal
		with XSwitchSignal
		with UnitSorterSignal

	trait ChannelSourceSignal extends ChannelSignal
		with MockSourceSignal
	trait ChannelSinkSignal extends ChannelSignal
		with MockSinkSignal

}
