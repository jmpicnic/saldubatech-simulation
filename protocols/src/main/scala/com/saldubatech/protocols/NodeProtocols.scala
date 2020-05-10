package com.saldubatech.protocols

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}

object NodeProtocols {
	trait Interface
	trait ProtocolSpec {
		type SIGNAL <: DomainSignal
		val demand: Interface
		val control: Interface
		val supply: Interface
		val managedObject: Interface
		val internal: Interface
	}

	trait ShuttleNodeSignal extends DomainSignal

	abstract class DemandParties {
		trait Request extends DomainSignal
			with ShuttleNodeSignal
		trait Response extends DomainSignal
	}

	object DemandProtocol extends DemandParties {
		trait JobCondition
		case class ByToteId(toteId: String) extends JobCondition
		object discovery {
			case class IsCapable(cnd: JobCondition) extends Identification.Impl() with Request
			case class CanPerform(request: IsCapable) extends Identification.Impl() with Response
			case class CannotPerform(request: IsCapable) extends Identification.Impl() with Response
		}
		object reserve {
			case class Reservation[REQUESTER >: Request <: DomainSignal](load: MaterialLoad, from: SimRef[REQUESTER], expirationTime: Option[Tick] = None) extends Identification.Impl()

			case class Reserve(cnd: JobCondition, expirationTime: Option[Tick]) extends Identification.Impl() with Request
			case class ReservationComplete(reservationId: String, request: Reserve, expireDelay: Option[Delay] = None) extends Identification.Impl() with Response
			case class ReservationRejected(request: Reserve, reason: String) extends Identification.Impl() with Response
			case class ReservationExpired(reservationId: String) extends Identification.Impl() with Response
		}
		object execute {
			trait Job
			trait Fulfillment

			case class Execute(reservationId: String, job: Job) extends Identification.Impl() with Request
			case class PartialFulfillment(request: Execute, fulfillment: Fulfillment) extends Identification.Impl() with Response
			case class CompleteFulfillment(request: Execute, fulfillment: Fulfillment) extends Identification.Impl() with Response
			case class FailedFulfillment(request: Execute, partials: Seq[Fulfillment]) extends Identification.Impl() with Response
			case class InvalidJob(request: Execute, reason: String) extends Identification.Impl() with Response
		}
	}


}
