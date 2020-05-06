package com.saldubatech.nodes

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.protocols.EquipmentManagement
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.units.carriage.SlotLocator

object ShuttleController {
	trait JobCondition
	trait Reservation
	trait Job
	trait Fulfillment

	sealed trait ShuttleDemandResponse extends Identification

	case class IsCapable(cnd: JobCondition) extends Identification.Impl() with EquipmentManagement.ShuttleDemandRequest
	case class CanPerform(request: IsCapable) extends Identification.Impl() with ShuttleDemandResponse
	case class CannotPerform(request: IsCapable) extends Identification.Impl() with ShuttleDemandResponse

	case class Reserve(cnd: JobCondition, expiration: Option[Delay]) extends Identification.Impl() with EquipmentManagement.ShuttleDemandRequest
	case class ReservationComplete(reservation: Reservation, request: Reserve) extends Identification.Impl() with ShuttleDemandResponse
	case class ReservationRejected(request: Reserve, reason: String) extends Identification.Impl() with ShuttleDemandResponse
	case class ReservationExpired(request: Reserve) extends Identification.Impl() with ShuttleDemandResponse

	case class Execute(reservationId: String, job: Job) extends Identification.Impl() with EquipmentManagement.ShuttleDemandRequest
	case class PartialFulfillment(request: Execute, fulfillment: Fulfillment) extends Identification.Impl() with ShuttleDemandResponse
	case class CompleteFulfillment(request: Execute, fulfillment: Fulfillment) extends Identification.Impl() with ShuttleDemandResponse
	case class FailedFulfillment(request: Execute, partials: Seq[Fulfillment]) extends Identification.Impl() with ShuttleDemandResponse
	case class InvalidJob(request: Execute, reason: String) extends Identification.Impl() with ShuttleDemandResponse

	case class Configuration()
}

class ShuttleController(val name: String, configuration: ShuttleController.Configuration, initial: Map[SlotLocator, MaterialLoad])
	extends Identification.Impl(name) {


}
