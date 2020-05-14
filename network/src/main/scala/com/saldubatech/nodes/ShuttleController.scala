package com.saldubatech.nodes

import com.saldubatech.base
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.AgentTemplate.DomainRun
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.protocols.Equipment.ShuttleSignal
import com.saldubatech.protocols.NodeProtocols.DemandProtocol.execute.Fulfillment
import com.saldubatech.protocols.{MaterialLoad, NodeProtocols}
import com.saldubatech.protocols.NodeProtocols.{Interface, ProtocolSpec, ShuttleNodeSignal}
import com.saldubatech.units.shuttle.LoadAwareShuttle

import scala.collection.mutable

object ShuttleController {
	object Protocol extends ProtocolSpec {
		override type SIGNAL = ShuttleNodeSignal
		object demand extends Interface{
			import com.saldubatech.protocols.NodeProtocols.DemandProtocol._
			type IsCapable = discovery.IsCapable
			type Reservation[REQUESTER >: Request <: DomainSignal] = reserve.Reservation[REQUESTER]
			type Reserve = reserve.Reserve
			type Execute = execute.Execute
		}
		object managedObject extends Interface {
			import com.saldubatech.units.shuttle.LoadAwareShuttle
			type  FailedEmpty = LoadAwareShuttle.FailedEmpty
			type  FailedBusy = LoadAwareShuttle.FailedBusy
			type  NotAcceptedCommand = LoadAwareShuttle.NotAcceptedCommand
			type  MaxCommandsReached = LoadAwareShuttle.MaxCommandsReached
			type  CompletedCommand = LoadAwareShuttle.CompletedCommand
			type  LoadArrival = LoadAwareShuttle.LoadArrival
			type  LoadAcknowledged = LoadAwareShuttle.LoadAcknowledged
			type  CompletedConfiguration = LoadAwareShuttle.CompletedConfiguration
		}
		object control extends Interface{
			case class Configure(shuttle: SimRef[ShuttleSignal]) extends base.Identification.Impl() with ShuttleNodeSignal
		}
		object supply extends Interface{}
		object internal extends Interface {
			import com.saldubatech.protocols.NodeProtocols.DemandProtocol._
			case class Expire[REQUESTER >: Request <: DomainSignal](r: demand.Reservation[REQUESTER]) extends base.Identification.Impl() with ShuttleNodeSignal
		}
	}
	case class Configuration()
}

class ShuttleController(override val name: String, configuration: ShuttleController.Configuration, initial: List[MaterialLoad])
	extends Node[ShuttleNodeSignal, ShuttleController](name) {
	import ShuttleController._
	import Protocol.control
	import Protocol.internal
	import com.saldubatech.protocols.NodeProtocols.DemandProtocol._

	private val inventory = mutable.Map[String, MaterialLoad](initial.map(ld => ld.uid -> ld): _*)


	override def booter: AgentTemplate.DomainConfigure[SIGNAL] = new AgentTemplate.DomainConfigure[SIGNAL] {
		override def configure(config: SIGNAL)(implicit ctx: CTX): AgentTemplate.DomainMessageProcessor[SIGNAL] = AgentTemplate.DomainRun[SIGNAL] {
			case control.Configure(shuttle) =>
				installProcessor(shuttle)
				runner
		}
	}

	private def runner: RUNNER = {
			implicit ctx: CTX => {
				case _ => runner
			}
	}

	val inProgress = mutable.Map.empty[LoadAwareShuttle.ExternalCommand, Fulfillment]
	val executeHandler: DomainRun[SIGNAL] = {
				// TO BE REVIEWED
		implicit ctx: CTX => {
			case cmd@execute.Execute(reservId, job) =>
				val maybeRsv = reservations.get(reservId)
				if (maybeRsv isEmpty) ctx.reply(execute.InvalidJob(cmd, s"Reservation does not exist"))
				DomainRun.same
			case LoadAwareShuttle.CompletedCommand(execCmd@LoadAwareShuttle.Store(ld, _)) =>
				inventory += ld.uid -> ld
				/*inProgress.get(execCmd).map(_.applyTask(execCmd)) match { // Either[Job, Job]
					case Left((job, fulfillment)) => ctx.signal(job.requester, execute.PartialFulfillment(job.request, fulfillment))
					case Right((job, fulfillment)) =>
						ctx.signal(job.requester, execute.CompleteFulfillment(job.request, fulfillment))
						inProgress -= execCmd
						DomainRun.same
					case LoadAwareShuttle.CompletedCommand(LoadAwareShuttle.Retrieve(ld, _)) =>
						inventory -= ld.uid
						DomainRun.same
				}*/
				DomainRun.same
		}
	}

		val expire: DomainRun[SIGNAL] = {
			implicit ctx: CTX => {
				case internal.Expire(rsv) =>
					reservations.remove(rsv.uid)
					inventory += rsv.load.uid -> rsv.load
					ctx.signal(rsv.from, reserve.ReservationExpired(rsv.uid))
					DomainRun.same
			}
		}

		private val reservations = mutable.Map.empty[String, reserve.Reservation[_]]
		val inventoryHandlerHandler: DomainRun[SIGNAL] = {
			implicit ctx: CTX => {
				case r@reserve.Reserve(cnd, exp) =>
					checkReservation(cnd, ctx.from) match {
						case None => ctx.reply(reserve.ReservationRejected(r, "Not Available"))
						case Some(rsv@reserve.Reservation(load, from, maybeExpire)) =>
									maybeExpire.foreach(ctx.signalSelf(internal.Expire(rsv), _))
									reservations += rsv.uid -> rsv
									inventory -= rsv.load.uid
					}
					DomainRun.same
			}
		}


		private val discoveryHandler: DomainRun[SIGNAL] = {
			implicit ctx: CTX => {
				case r@discovery.IsCapable(cnd) =>
					checkReservation(cnd, ctx.from).map(_ => discovery.CanPerform(r)).orElse(Some(discovery.CannotPerform(r))).foreach(msg => ctx.reply(msg))
					DomainRun.same
			}
		}

		private def checkReservation[REQUESTER >: Request <: DomainSignal](request: JobCondition, from: SimRef[REQUESTER], expiration: Option[Tick] = None): Option[reserve.Reservation[REQUESTER]] = request match {
			case ByToteId(id) =>
				inventory.get(id).map(ld => reserve.Reservation[REQUESTER](ld, from, expiration))
		}

}
