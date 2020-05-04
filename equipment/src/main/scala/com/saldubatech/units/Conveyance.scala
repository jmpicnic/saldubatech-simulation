package com.saldubatech.units

import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.lift.LoadAwareXSwitch
import com.saldubatech.units.shuttle.LoadAwareShuttle
import com.saldubatech.units.unitsorter.{UnitSorter, UnitSorterSignal}

object Conveyance {
	class LoadAwareShuttleToLoadAwareLift(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, LoadAwareShuttle.LoadAwareShuttleSignal, LoadAwareXSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with LoadAwareShuttle.EfferentChannel with LoadAwareXSwitch.AfferentChannel

	class LoadAwareLiftToLoadAwareShuttle(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, LoadAwareXSwitch.XSwitchSignal, LoadAwareShuttle.LoadAwareShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with lift.LoadAwareXSwitch.EfferentChannel
			with LoadAwareShuttle.AfferentChannel

	class LoadAwareLiftToUnitSorter(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, LoadAwareXSwitch.XSwitchSignal, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with lift.LoadAwareXSwitch.EfferentChannel
			with UnitSorter.AfferentChannel

	class UnitSorterToLoadAwareLift(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, UnitSorterSignal, LoadAwareXSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with UnitSorter.EfferentChannel
			with LoadAwareXSwitch.AfferentChannel


}
