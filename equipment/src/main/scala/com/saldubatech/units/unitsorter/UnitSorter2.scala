package com.saldubatech.units.unitsorter

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.`abstract`.{EquipmentManager, EquipmentUnit}

import scala.collection.mutable

trait UnitSorterSignal2 extends Identification



object UnitSorter2 extends EquipmentUnit.Definitions[UnitSorterSignal2, UnitSorterSignal2, UnitSorterSignal2] {

	abstract class ConfigurationCommand extends Identification.Impl() with UnitSorterSignal2
	case object NoConfigure extends ConfigurationCommand

	abstract class ExternalCommand extends Identification.Impl() with UnitSorterSignal2

	case class Sort(load: MaterialLoad, destination: String) extends ExternalCommand

	abstract class Notification extends Identification.Impl() with EquipmentManager.Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class MaxRoutingReached(cmd: Sort) extends Notification
	case class LoadArrival(load: MaterialLoad, channel: String) extends Notification

	abstract class InternalSignal extends Identification.Impl() with UnitSorterSignal2
	case object Arrive extends InternalSignal
	case class Deliver(load: MaterialLoad, dest: Int, cmd: ExternalCommand) extends InternalSignal
	case class EmptyArrive(at: Int) extends InternalSignal

	case class Configuration(name: String, maxRoutingMap: Int, nTrays: Int,
	                         inducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal2]],
	                         discharges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal2, _]],
	                         physics: CircularPathTravel)

	private def inductSink(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, _, UnitSorterSignal2], host: Processor.Ref) =
		new InductSink(manager, chOps, host) {
			override def loadArrived(endpoint: Channel.End[MaterialLoad, UnitSorterSignal2], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal2]): RUNNER = {
				ctx.aCtx.log.debug(s"Load Arrived: $load at ${endpoint.channelName}")
				ctx.signal(manager, LoadArrival(load, endpoint.channelName))
				Processor.DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, UnitSorterSignal2], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal2]): RUNNER =
				Processor.DomainRun.same

		}.end

	private def dischargeSource(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, UnitSorterSignal2, _], host: Processor.Ref) =
		new DischargeSource(manager, chOps, host) {
			override def loadAcknowledged(ep: Channel.Start[MaterialLoad, UnitSorterSignal2], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[UnitSorterSignal2]): RUNNER =
				Processor.DomainRun.same

		}.start

	def buildProcessor(configuration: Configuration)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref): Processor[UnitSorterSignal2] =
		new Processor[UnitSorterSignal2](configuration.name, clockRef, simController, new UnitSorter2(configuration).configurer)
}

class UnitSorter2(configuration: UnitSorter2.Configuration) {
	import UnitSorter2._

	private val routing: mutable.Map[MaterialLoad, (Int, Sort)] = mutable.Map.empty

	private var manager: Processor.Ref = _
	private var discharges: Map[Int, Channel.Start[MaterialLoad, UnitSorterSignal2]] = _
	private var dischargeIndex: Map[String, Int] = _
	private var dischargeListener: RUNNER = _
	private var inducts: Map[Int, Channel.End[MaterialLoad, UnitSorterSignal2]] = _
	private var inductListener: RUNNER = _
	private val trays: mutable.Map[Int, MaterialLoad] = mutable.Map.empty//((0 until configuration.nTrays).map((idx: Int) => idx -> None): _*)
	private def trayAt(idx: Int): Option[MaterialLoad] = trays.get(idx)
	private def trayLoad(idx: Int, load: MaterialLoad): Unit = trays += idx -> load
	private def trayEmpty(idx: Int): Unit = trays.remove(idx)

	lazy val endpointListener = dischargeListener orElse inductListener

	trait PendingOperation
	private case class PendingPickup(tray: Int, induct: Int, channelEnd: Channel.End[MaterialLoad, UnitSorterSignal2]) extends PendingOperation
	private case class PendingDelivery(tray: Int, discharge: Int, channelStart: Channel.Start[MaterialLoad, UnitSorterSignal2]) extends PendingOperation
	private trait SorterState
	private case class RunningSorterState(at: Tick, northPosition: Int, pendingDeliveries: Set[PendingDelivery], pendingPickups: Set[PendingPickup])
	private case object IdleSorter extends SorterState
	private var sorterState: SorterState = IdleSorter

	private def configurer: Processor.DomainConfigure[UnitSorterSignal2] = new Processor.DomainConfigure[UnitSorterSignal2] {
		override def configure(config: UnitSorterSignal2)(implicit ctx: Processor.SignallingContext[UnitSorterSignal2]): Processor.DomainMessageProcessor[UnitSorterSignal2] = {
			manager = ctx.from
			inducts = configuration.inducts.map { case (idx, ch) => idx -> inductSink(manager, ch, ctx.aCtx.self) }
			inductListener = inducts.values.map(_.loadReceiver).reduce(_ orElse _)
			discharges = configuration.discharges.map { case (idx, ch) => idx -> dischargeSource(manager, ch, ctx.aCtx.self) }
			dischargeIndex = configuration.discharges.map { case (idx, ch) => ch.ch.name -> idx }
			dischargeListener = discharges.values.map(_.ackReceiver).reduce(_ orElse _)
			(0 until configuration.nTrays) foreach nextEmptyTravel
			ctx.reply(UnitSorter2.CompletedConfiguration(ctx.aCtx.self))
			RUNNING
		}
	}

	private def deliver(delivery: PendingDelivery)(implicit ctx: CTX): Option[PendingDelivery] = {
		if(trayAt(delivery.discharge).exists(delivery.channelStart.send(_))) {
			trayEmpty(delivery.discharge)
			None
		}
		else {
			val targetRecirculate = configuration.physics.oneTurnTime
			ctx.signalSelf(Arrive, targetRecirculate)
			Some(delivery)
		}
	}

	private def pickup(pickup: PendingPickup, destination: Int)(implicit ctx: CTX): PendingOperation = {
		val inductContents = pickup.channelEnd.peekNext
		if(inductContents.nonEmpty && trayAt(pickup.tray).isEmpty) {
			pickup.channelEnd.getNext
			trayLoad(pickup.tray, inductContents.head._1)
			val travelTime = configuration.physics.travelTime(pickup.induct, destination)
			ctx.signalSelf(Arrive, travelTime)
			PendingDelivery(pickup.tray, destination, discharges(destination))
		} else {
			ctx.signalSelf(Arrive, configuration.physics.oneTurnTime)
			pickup
		}
	}

	lazy val RUNNING: RUNNER = endpointListener orElse {
		ctx: CTX => {
			//refreshState
			{
				case cmd @ Sort(load, destination) =>
					assert(dischargeIndex contains destination, s"$destination is not a known discharge channel")
					if(routing.size < configuration.maxRoutingMap) {
						routing += load -> (dischargeIndex(destination), cmd)
					}
					else ctx.reply(MaxRoutingReached(cmd))
					RUNNING
				case Arrive => RUNNING
			}
		}
	}

	private def nextEmptyTravel(at: Int)(implicit ctx: Processor.SignallingContext[UnitSorterSignal2]): RUNNER = {
		val next = configuration.inducts.find(_._1 > at).getOrElse(configuration.inducts.head)._1
		ctx.signalSelf(EmptyArrive(next), configuration.physics.travelTime(at, next))
		Processor.DomainRun.same
	}
}
