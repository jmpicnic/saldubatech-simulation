package com.saldubatech.units

import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.protocols.{Equipment, MaterialLoad}
import com.saldubatech.transport.Channel
import com.saldubatech.units.lift.LoadAwareXSwitch
import com.saldubatech.units.shuttle.LoadAwareShuttle
import com.saldubatech.units.unitsorter.UnitSorter

object Conveyance {
	class LoadAwareShuttleToLoadAwareLift(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.ShuttleSignal, Equipment.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with LoadAwareShuttle.EfferentChannel with LoadAwareXSwitch.AfferentChannel

	class LoadAwareLiftToLoadAwareShuttle(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.XSwitchSignal, Equipment.ShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with lift.LoadAwareXSwitch.EfferentChannel
			with LoadAwareShuttle.AfferentChannel

	class LoadAwareLiftToUnitSorter(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.XSwitchSignal, Equipment.UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with lift.LoadAwareXSwitch.EfferentChannel
			with UnitSorter.AfferentChannel

	class UnitSorterToLoadAwareLift(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.UnitSorterSignal, Equipment.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
			with UnitSorter.EfferentChannel
			with LoadAwareXSwitch.AfferentChannel
}
