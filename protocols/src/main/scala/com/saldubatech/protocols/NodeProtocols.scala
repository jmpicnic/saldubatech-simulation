package com.saldubatech.protocols

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Simulation.DomainSignal

object NodeProtocols {
	trait Accepted {
		trait Signal extends DomainSignal
		abstract class Configure extends Identification.Impl() with Signal
		trait Command extends Signal
		trait Demand extends Signal
		trait Supply extends Signal
		trait Notification extends Signal
	}
	trait Emitted {
		abstract class Demand extends Identification.Impl() with DomainSignal
		abstract class Supply extends Identification.Impl() with DomainSignal
		abstract class Notification extends Identification.Impl() with DomainSignal
	}

	class Protocol {
		object ACCEPTED extends Accepted
		object EMITTED extends Emitted
	}

	object ShuttleController extends Protocol

}
