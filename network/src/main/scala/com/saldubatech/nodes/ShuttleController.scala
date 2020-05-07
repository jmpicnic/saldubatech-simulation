package com.saldubatech.nodes

import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.AgentTemplate.Ref
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.protocols.Equipment.ShuttleSignal
import com.saldubatech.protocols.NodeProtocols.{ShuttleController => Protocol}
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.units.carriage.SlotLocator

object ShuttleController {
	trait JobCondition
	trait Reservation
	trait Job
	trait Fulfillment

	case class Configure(shuttle: Ref[ShuttleSignal]) extends Protocol.ACCEPTED.Configure

	case class IsCapable(cnd: JobCondition) extends Identification.Impl() with Protocol.ACCEPTED.Demand
	case class CanPerform(request: IsCapable) extends Protocol.EMITTED.Demand
	case class CannotPerform(request: IsCapable) extends Protocol.EMITTED.Demand

	case class Reserve(cnd: JobCondition, expiration: Option[Delay]) extends Identification.Impl() with Protocol.ACCEPTED.Demand
	case class ReservationComplete(reservation: Reservation, request: Reserve) extends Protocol.EMITTED.Demand
	case class ReservationRejected(request: Reserve, reason: String) extends Protocol.EMITTED.Demand
	case class ReservationExpired(request: Reserve) extends Protocol.EMITTED.Demand

	case class Execute(reservationId: String, job: Job) extends Identification.Impl() with Protocol.ACCEPTED.Demand
	case class PartialFulfillment(request: Execute, fulfillment: Fulfillment) extends Protocol.EMITTED.Demand
	case class CompleteFulfillment(request: Execute, fulfillment: Fulfillment) extends Protocol.EMITTED.Demand
	case class FailedFulfillment(request: Execute, partials: Seq[Fulfillment]) extends Protocol.EMITTED.Demand
	case class InvalidJob(request: Execute, reason: String) extends Protocol.EMITTED.Demand

	case class Configuration()
}

class ShuttleController(override val name: String, configuration: ShuttleController.Configuration, initial: Map[SlotLocator, MaterialLoad])
	extends Node[Protocol.ACCEPTED.Signal, ShuttleController](name, Protocol) {
	import ShuttleController._

	override def booter: AgentTemplate.DomainConfigure[SIGNAL] = new AgentTemplate.DomainConfigure[SIGNAL] {
		override def configure(config: SIGNAL)(implicit ctx: CTX): AgentTemplate.DomainMessageProcessor[SIGNAL] = AgentTemplate.DomainRun[SIGNAL]{
			case Configure(shuttle) =>
				installProcessor(shuttle)
				runner
		}

		private def runner: RUNNER = {
			implicit ctx: CTX => {
				case _ => runner
			}
		}
	}
}
