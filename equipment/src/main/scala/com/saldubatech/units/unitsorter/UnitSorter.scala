package com.saldubatech.units.unitsorter

import java.util.concurrent.atomic.AtomicReference

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.`abstract`.{EquipmentManager, EquipmentUnit}
import com.saldubatech.util.LogEnabled

import scala.collection.{SortedMap, mutable}

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
	case class Arrive(msg: String) extends InternalSignal
	case class Deliver(load: MaterialLoad, dest: Int, cmd: ExternalCommand) extends InternalSignal
	case class EmptyArrive(at: Int) extends InternalSignal

	case class Configuration(name: String, maxRoutingMap: Int,
	                         inducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]],
	                         discharges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]],
	                         physics: CircularPathTravel)


	def buildProcessor(configuration: Configuration)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref): Processor[UnitSorterSignal] =
		new Processor[UnitSorterSignal](configuration.name, clockRef, simController, new UnitSorter(configuration).configurer)
}

class UnitSorter(configuration: UnitSorter.Configuration) extends LogEnabled {

	import UnitSorter._

	private case class PendingSortCommand(toDischarge: Int, sort: Sort)

	private val receivedCommands: mutable.Map[MaterialLoad, PendingSortCommand] = mutable.Map.empty

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
			ctx.reply(UnitSorter.CompletedConfiguration(ctx.aCtx.self))
			RUNNING
		}
	}

	private def inductSink(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, _, UnitSorterSignal], host: Processor.Ref) =
		new InductSink(manager, chOps, host) {
			override def loadArrived(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				//ctx.aCtx.log.info(s"Load Arrived: $load at ${endpoint.channelName}")
				ctx.signal(manager, LoadArrival(load, endpoint.channelName))
				cycleAndSignalNext
				Processor.DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				Processor.DomainRun.same
			}
		}.end

	private def dischargeSource(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, _], host: Processor.Ref) =
		new DischargeSource(manager, chOps, host) {
			override def loadAcknowledged(ep: Channel.Start[MaterialLoad, UnitSorterSignal], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER =
				Processor.DomainRun.same

		}.start

	private val loadedTrays = mutable.Map.empty[Int, MaterialLoad]
	/*
	Unload all the "full" trays that have arrived to their destination and send CompleteCommand notifications to the manager.
	 */
	//import Ordering._
	private val pendingCommands = mutable.Map.empty[MaterialLoad, PendingSortCommand]
	private val scheduledDeliveries = mutable.Map.empty[MaterialLoad, PendingSortCommand]
	private def dischargeArrivals(sorterAt: configuration.physics.Position)(implicit ctx: CTX): Option[Delay] = {
		//log.info(s"Discharging: Time is ${ctx.now} with currentZero: ${sorterAt.slotAtZero}")
		//log.info(s"Discharging: Loaded Trays: $loadedTrays at ${loadedTrays.map(tk => sorterAt.indexForSlot(tk._1))}")
		//log.info(s"Discharging: pending commands: $pendingCommands")
		val candidates = for {
			(dischargeIdx, discharge) <- discharges
			tray = sorterAt.slotAtIndex(dischargeIdx)
			load <- loadedTrays.get(tray)
			cmd <- scheduledDeliveries.get(load).filter(_.toDischarge == dischargeIdx).map(_.sort)
		} yield {
			//log.info(s"Candidate Discharge load($load) from Tray($tray) at position($dischargeIdx) through channel(${discharge.channelName} with command($cmd)")
			println(s"### Load Ready to send $load from tray $tray into ${discharge.channelName}")
			if (discharge.send(load)) {
				println(s"### Sending load $load from tray $tray into ${discharge.channelName}")
				loadedTrays -= tray
				scheduledDeliveries -= load
				ctx.signal(manager, CompletedCommand(cmd))
				None
			} else {
				println(s"### Cannot send load $load fron tray $tray into ${discharge.channelName}, taking turn around.")
				Some(configuration.physics.oneTurnTime)
			}
		}
		//log.info(s"DischargeArrivals Candidates: $candidates")
		if(candidates isEmpty) None else candidates.min
	}



	/*
	Reconcile all pending routing commands with any available loads in inducts that have not been matched yet.
	 */
	private def assignCommands(sorterAt: configuration.physics.Position)(implicit ctx: CTX): Option[Delay] = {
		val candidates = for {
			trayPosition <- (0 until configuration.physics.nSlots).filter(!loadedTrays.contains(_)).map(sorterAt.indexForSlot) // positions of empty trays
			(availableLoadPosition, load) <- inducts.flatMap{ case (i, induct) => induct.peekNext.map(i -> _._1)}
			pendingCmd <- receivedCommands.get(load)
		} yield (trayPosition, availableLoadPosition, pendingCmd)
		//log.info(s"AssignCommands PickupCandidates: $candidates")
		if(candidates nonEmpty) {
			val (trayPosition, pickupPosition, pendingCommand) = candidates.minBy(entry => (entry._2+configuration.physics.nSlots - entry._1)%configuration.physics.nSlots)
			receivedCommands -= pendingCommand.sort.load
			pendingCommands += pendingCommand.sort.load -> pendingCommand
			val nextTime =
				if(trayPosition == pickupPosition) None
				else Some(configuration.physics.travelTime(trayPosition, pickupPosition))
			//log.info(s">>>>> Found Candidate: $pendingCommand for tray at Position $trayPosition to pick up at $pickupPosition, next event at: $nextTime")
			nextTime
		} else None
	}
	/*
	Induct any loads in Inducts that:
	1. Have a command pending for them.
	2. Have an empty tray in front of them.
	Collect "Deliver" Event candidates.
	 */
	private def inductPickups(sorterAt: configuration.physics.Position)(implicit ctx: CTX): Unit =
		for{
			(idx, induct) <- inducts
			(load, _) <- induct.peekNext
			tray = sorterAt.slotAtIndex(idx)
			pendingCmd <- pendingCommands.get(load) if (!loadedTrays.contains(tray))
		} {
			loadedTrays += tray -> load
			println(s"### Loading tray $tray with $load")
			induct.getNext
			//log.info(s"InductPickups: Loading Tray $tray at $idx with $load")
		}

	private def inTransitLoadDischarges(sorterAt: configuration.physics.Position)(implicit ctx: CTX): Option[Delay] = {
		val candidates = for{
			(tr, load) <- loadedTrays
			pendingCmd <- pendingCommands.get(load)
		} yield {
			println(s"### InTransitLoad $load in tray $tr with cmd: $pendingCmd")
			pendingCommands -= load
			scheduledDeliveries += load -> pendingCmd
			configuration.physics.travelTime(sorterAt.indexForSlot(tr), pendingCmd.toDischarge)
		}
		//log.info(s"inTransitLoads Candidates: $candidates")
		if (candidates isEmpty) None else Some(candidates.min)
	}
	private def cycleAndSignalNext(implicit ctx: CTX) = {
		val currentPosition = new configuration.physics.Position(ctx.now)
		val nextArrival = dischargeArrivals(currentPosition)
		val nextCommandFirstPass = assignCommands(currentPosition)
		inductPickups(currentPosition)
		val nextCommandSecondPass = assignCommands(currentPosition)
		val nextDischargeFromInTransit = inTransitLoadDischarges(currentPosition)
		val candidates = Seq(
			nextArrival, nextCommandFirstPass, nextCommandSecondPass, nextDischargeFromInTransit
		).flatten
		//log.info(s"CycleAndSignal: Target Times Candidates: $candidates")
		if(candidates nonEmpty) {
			val target = candidates.min
			if(target == 0) throw new IllegalStateException(s"A delay of 0 should not happen")
			ctx.signalSelf(Arrive(s"With Delay: $target"), target)
		}
	}

	lazy val RUNNING: RUNNER = endpointListener orElse {
		implicit ctx: CTX => {
			case sortCmd@Sort(load, destination) =>
				//log.info(s"Got Command $sortCmd at ${ctx.now}")
				assert(dischargeIndex contains destination, s"$destination is not a known discharge channel")
				if (receivedCommands.size < configuration.maxRoutingMap) receivedCommands += load -> PendingSortCommand(dischargeIndex(destination), sortCmd)
				else ctx.reply(MaxRoutingReached(sortCmd))
				cycleAndSignalNext
				RUNNING
			case Arrive(_) =>
				cycleAndSignalNext
				RUNNING
		}
	}
}
