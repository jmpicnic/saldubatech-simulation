package com.saldubatech.units.unitsorter

import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.`abstract`.{EquipmentManager, EquipmentUnit}

import scala.collection.mutable

trait UnitSorterSignal extends Identification



object UnitSorter extends EquipmentUnit.Definitions[UnitSorterSignal, UnitSorterSignal, UnitSorterSignal] {

	abstract class ConfigurationCommand extends Identification.Impl() with UnitSorterSignal
	case object NoConfigure extends ConfigurationCommand

	abstract class ExternalCommand extends Identification.Impl() with UnitSorterSignal

	case class Sort(load: MaterialLoad, destination: String) extends ExternalCommand

	abstract class Notification extends Identification.Impl() with EquipmentManager.Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class MaxRoutingReached(cmd: Sort) extends Notification
	case class LoadArrival(load: MaterialLoad, channel: String) extends Notification

	abstract class InternalSignal extends Identification.Impl() with UnitSorterSignal
	case class Deliver(load: MaterialLoad, dest: Int, cmd: ExternalCommand) extends InternalSignal
	case class EmptyArrive(at: Int) extends InternalSignal

	case class Configuration(name: String, maxRoutingMap: Int, nTrays: Int,
	                         inducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]],
	                         discharges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]],
	                         physics: CircularPathTravel)

	private def inductSink(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, _, UnitSorterSignal], host: Processor.Ref) =
		new InductSink(manager, chOps, host) {
			override def loadArrived(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				ctx.aCtx.log.debug(s"Load Arrived: $load at ${endpoint.channelName}")
				ctx.signal(manager, LoadArrival(load, endpoint.channelName))
				Processor.DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER =
				Processor.DomainRun.same

		}.end

	private def dischargeSource(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, _], host: Processor.Ref) =
		new DischargeSource(manager, chOps, host) {
			override def loadAcknowledged(ep: Channel.Start[MaterialLoad, UnitSorterSignal], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER =
				Processor.DomainRun.same

		}.start

	def buildProcessor(configuration: Configuration)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref): Processor[UnitSorterSignal] =
		new Processor[UnitSorterSignal](configuration.name, clockRef, simController, new UnitSorter(configuration).configurer)
}

class UnitSorter(configuration: UnitSorter.Configuration) {
	import UnitSorter._

	private val routing: mutable.Map[MaterialLoad, (Int, Sort)] = mutable.Map.empty

	private var manager: Processor.Ref = _
	private var discharges: Map[Int, Channel.Start[MaterialLoad, UnitSorterSignal]] = _
	private var dischargeIndex: Map[String, Int] = _
	private var dischargeListener: RUNNER = _
	private var inducts: Map[Int, Channel.End[MaterialLoad, UnitSorterSignal]] = _
	private var inductListener: RUNNER = _

	lazy val endpointListener = dischargeListener orElse inductListener

	private def configurer: Processor.DomainConfigure[UnitSorterSignal] = new Processor.DomainConfigure[UnitSorterSignal] {
		override def configure(config: UnitSorterSignal)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): Processor.DomainMessageProcessor[UnitSorterSignal] = {
			manager = ctx.from
			inducts = configuration.inducts.map { case (idx, ch) => idx -> inductSink(manager, ch, ctx.aCtx.self) }
			inductListener = inducts.values.map(_.loadReceiver).reduce(_ orElse _)
			discharges = configuration.discharges.map { case (idx, ch) => idx -> dischargeSource(manager, ch, ctx.aCtx.self) }
			dischargeIndex = configuration.discharges.map { case (idx, ch) => ch.ch.name -> idx }
			dischargeListener = discharges.values.map(_.ackReceiver).reduce(_ orElse _)
			(0 until configuration.nTrays) foreach nextEmptyTravel
			ctx.reply(UnitSorter.CompletedConfiguration(ctx.aCtx.self))
			RUNNING
		}
	}


	lazy val RUNNING: RUNNER = endpointListener orElse {
		ctx: CTX => {
			case cmd @ Sort(load, destination) =>
				assert(dischargeIndex contains destination, s"$destination is not a known discharge channel")
				if(routing.size < configuration.maxRoutingMap) routing += load -> (dischargeIndex(destination), cmd)
				else ctx.reply(MaxRoutingReached(cmd))
				RUNNING
			case cmd @ Deliver(load, at, extCmd) =>
				if(discharges.get(at).exists(_.send(load)(ctx))){ // Continue empty
					ctx.signal(manager, CompletedCommand(extCmd))
					nextEmptyTravel(at)(ctx)
				}
				else { // Recirculate
					ctx.signalSelf(EmptyArrive(at), configuration.physics.oneTurnTime)
					Processor.DomainRun.same
				}
			case cmd @ EmptyArrive(at) =>
				// Reluctant Induction: Only induct if a sortation destination is given.
				// ==> Alternative. Eager Induction and check at the arrival to each discharge for routing info.
				(for {
					induct <- inducts.get(at)
					ld <- induct.peekNext(ctx).map(_._1)
					(idx, cmd) <- routing.get(ld)
				} yield {
					ctx.aCtx.log.info(s"Loading Load $ld on position $at at time ${ctx.now}")
					induct.getNext(ctx)
					routing -= ld
					Deliver(ld, idx, cmd)
				}).map(deliver => ctx.signalSelf(deliver, configuration.physics.travelTime(at, deliver.dest))).getOrElse{nextEmptyTravel(at)(ctx)}
				Processor.DomainRun.same
		}
	}

	private def nextEmptyTravel(at: Int)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
		val next = configuration.inducts.find(_._1 > at).getOrElse(configuration.inducts.head)._1
		ctx.signalSelf(EmptyArrive(next), configuration.physics.travelTime(at, next))
		Processor.DomainRun.same
	}
}
