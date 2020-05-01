package com.saldubatech.units.unitsorter

import java.util.concurrent.atomic.AtomicReference

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.abstractions.{EquipmentManager, EquipmentUnit}
import com.saldubatech.util.LogEnabled

import scala.collection.{SortedMap, mutable}

trait UnitSorterSignal extends Identification



object UnitSorter {//extends EquipmentUnit[UnitSorterSignal] {

	abstract class ConfigurationCommand extends Identification.Impl() with UnitSorterSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with UnitSorterSignal
	case class Sort(load: MaterialLoad, destination: String) extends ExternalCommand

	sealed abstract class Notification extends Identification.Impl() with EquipmentManager.Notification
	case class CompletedConfiguration(self: Processor.Ref) extends Notification
	case class CompletedCommand(cmd: ExternalCommand) extends Notification
	case class MaxCommandsReached(cmd: ExternalCommand) extends Notification
	case class LoadArrival(load: MaterialLoad, channel: String) extends Notification

	abstract class InternalSignal extends Identification.Impl() with UnitSorterSignal
	case class Arrive(msg: String) extends InternalSignal

	case class Configuration(maxRoutingMap: Int,
	                         inducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]],
	                         discharges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]],
	                         physics: CircularPathTravel)


	def buildProcessor(name: String, configuration: Configuration)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref): Processor[UnitSorterSignal] =
		new Processor[UnitSorterSignal](name, clockRef, simController, new UnitSorter(name, configuration).configurer)
}

class UnitSorter(override val name: String, configuration: UnitSorter.Configuration) extends EquipmentUnit[UnitSorterSignal] with LogEnabled {

	import UnitSorter._

	private var discharges: Map[Int, Channel.Start[MaterialLoad, UnitSorterSignal]] = _
	private var dischargeIndex: Map[String, Int] = _
	private var dischargeListener: RUNNER = _
	private var inducts: Map[Int, Channel.End[MaterialLoad, UnitSorterSignal]] = _
	private var inductListener: RUNNER = _

	lazy val endpointListener = dischargeListener orElse inductListener

	private def configurer: Processor.DomainConfigure[UnitSorterSignal] = new Processor.DomainConfigure[UnitSorterSignal] {
		override def configure(config: UnitSorterSignal)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): Processor.DomainMessageProcessor[UnitSorterSignal] = {
			installManager(ctx.from)
			installSelf(ctx.aCtx.self)
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
		new Channel.Sink[MaterialLoad, UnitSorterSignal] {
			lazy override val ref = host
			lazy val end = chOps.registerEnd(this)
			override def loadArrived(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				//ctx.aCtx.log.info(s"Load Arrived: $load at ${endpoint.channelName}")
				ctx.signal(manager, LoadArrival(load, endpoint.channelName))
				stopCycle
				Processor.DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				Processor.DomainRun.same
			}
		}.end

	private def dischargeSource(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, _], host: Processor.Ref) =
		new Channel.Source[MaterialLoad, UnitSorterSignal] {
			override lazy val ref = host
			lazy val start = chOps.registerStart(this)
			override def loadAcknowledged(ep: Channel.Start[MaterialLoad, UnitSorterSignal], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[UnitSorterSignal]): RUNNER = {
				stopCycle
				Processor.DomainRun.same
			}
		}.start

	private case class PendingSortCommand(toDischarge: Int, sort: Sort)
	private val receivedCommands: mutable.Map[MaterialLoad, PendingSortCommand] = mutable.Map.empty

	private val loadedTrays = mutable.Map.empty[Int, Sort]

	private lazy val RUNNING: RUNNER = endpointListener orElse {
		implicit ctx: CTX => {
			case sortCmd@Sort(load, destination) =>
				//log.info(s"Got Command $sortCmd at ${ctx.now}")
				assert(dischargeIndex contains destination, s"$destination is not a known discharge channel")
				if (receivedCommands.size < configuration.maxRoutingMap) receivedCommands += load -> PendingSortCommand(dischargeIndex(destination), sortCmd)
				else ctx.reply(MaxCommandsReached(sortCmd))
				stopCycle
				RUNNING
			case Arrive(_) =>
				stopCycle
				RUNNING
		}
	}

	private def doDischarges(sorterAt: configuration.physics.Position)(implicit ctx: CTX) = {
		for{
			(dischargeIdx, ep) <- discharges
			tray = sorterAt.slotAtIndex(dischargeIdx)
			cmd <- loadedTrays.filter(t => dischargeIndex(t._2.destination) == dischargeIdx).get(tray)
		} if(ep.send(cmd.load)) {
			receivedCommands.remove(cmd.load)
			loadedTrays -= tray
			ctx.signal(manager, CompletedCommand(cmd))
		}
	}
	private def doInducts(sorterAt: configuration.physics.Position)(implicit ctx: CTX) = {
		for{
			(inductIdx, ep) <- inducts
			tray = sorterAt.slotAtIndex(inductIdx)
			(load, _) <- ep.peekNext
			cmd <- receivedCommands.get(load)
		} if(!loadedTrays.contains(tray)) {
			loadedTrays += tray -> cmd.sort
			ep.getNext
		}
	}
	private def nextStop(sorterAt: configuration.physics.Position)(implicit ctx: CTX) = {
		val dischargeTimes = for{
			(tray, cmd) <- loadedTrays
			trayIdx = sorterAt.indexForSlot(tray)
			dischargeIdx <- dischargeIndex.get(cmd.destination)
		} yield if(trayIdx == dischargeIdx) configuration.physics.oneTurnTime else configuration.physics.travelTime(trayIdx, dischargeIdx)
		val inductTimes = for{
			emptyTraySlot <- (0 until configuration.physics.nSlots).filter(idx => !loadedTrays.contains(idx))
			(inductIdx, ep) <- inducts.filter(_._2.peekNext nonEmpty)
			cmd <- ep.peekNext.flatMap(t => receivedCommands.get(t._1))
			trayIdx = sorterAt.indexForSlot(emptyTraySlot)
		} yield if(trayIdx == inductIdx) configuration.physics.oneTurnTime else configuration.physics.travelTime(trayIdx, inductIdx)
		val candidates = dischargeTimes ++ inductTimes
		if(candidates nonEmpty) {
			val delay = candidates.min
			ctx.signalSelf(Arrive(s"with Delay $delay"), delay)
		}
	}
	var lastUpdate: Tick = 0
	private def stopCycle(implicit ctx: CTX) = {
		if(ctx.now != lastUpdate) {
			lastUpdate = ctx.now
			val sorterAt = new configuration.physics.Position(ctx.now)
			doDischarges(sorterAt)
			doInducts(sorterAt)
			nextStop(sorterAt)
		}
	}


}
