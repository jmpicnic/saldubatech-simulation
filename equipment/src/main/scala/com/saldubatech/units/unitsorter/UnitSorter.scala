package com.saldubatech.units.unitsorter

import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate._
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Simulation.DomainSignal
import com.saldubatech.ddes.{AgentTemplate, Clock, SimulationController}
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.protocols.Equipment.UnitSorterSignal
import com.saldubatech.protocols.{Equipment, EquipmentManagement}
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.abstractions.EquipmentUnit
import com.saldubatech.util.LogEnabled

import scala.collection.mutable


object UnitSorter {//extends EquipmentUnit[Equipment.UnitSorterSignal] {

	abstract class ConfigurationCommand extends Identification.Impl() with Equipment.UnitSorterSignal
	case object NoConfigure extends ConfigurationCommand

	sealed abstract class ExternalCommand extends Identification.Impl() with Equipment.UnitSorterSignal
	case class Sort(load: MaterialLoad, destination: String) extends ExternalCommand

	case class CompletedConfiguration(self: Ref[UnitSorterSignal]) extends Identification.Impl() with EquipmentManagement.UnitSorterNotification
	case class CompletedCommand(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.UnitSorterNotification
	case class MaxCommandsReached(cmd: ExternalCommand) extends Identification.Impl() with EquipmentManagement.UnitSorterNotification
	case class LoadArrival(load: MaterialLoad, channel: String) extends Identification.Impl() with EquipmentManagement.UnitSorterNotification

	abstract class InternalSignal extends Identification.Impl() with Equipment.UnitSorterSignal
	case class Arrive(msg: String) extends InternalSignal

	trait AfferentChannel extends Channel.Afferent[MaterialLoad, Equipment.UnitSorterSignal] { self =>
		override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.UnitSorterSignal
		override type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.UnitSorterSignal
		override type DeliverSignal = Channel.DeliverLoad[MaterialLoad] with Equipment.UnitSorterSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal
		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Distance) = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.UnitSorterSignal
		override def deliverBuilder(channel: String) = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.UnitSorterSignal
	}

	trait EfferentChannel extends Channel.Efferent[MaterialLoad, Equipment.UnitSorterSignal] {
		override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.UnitSorterSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String) = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal
	}


	case class Configuration(maxRoutingMap: Int,
	                         inducts: Map[Int, Channel.Ops[MaterialLoad, _, Equipment.UnitSorterSignal]],
	                         discharges: Map[Int, Channel.Ops[MaterialLoad, Equipment.UnitSorterSignal, _]],
	                         physics: CircularPathTravel)


	def buildProcessor(name: String, configuration: Configuration)(implicit clockRef: Clock.Ref, simController: SimulationController.Ref):  AgentTemplate.Wrapper[Equipment.UnitSorterSignal] =
		new  AgentTemplate.Wrapper[Equipment.UnitSorterSignal](name, clockRef, simController, new UnitSorter(name, configuration).configurer)
}

class UnitSorter(override val name: String, configuration: UnitSorter.Configuration) extends EquipmentUnit[Equipment.UnitSorterSignal] with LogEnabled {

	import UnitSorter._

	private var discharges: Map[Int, Channel.Start[MaterialLoad, Equipment.UnitSorterSignal]] = _
	private var dischargeIndex: Map[String, Int] = _
	private var dischargeListener: RUNNER = _
	private var inducts: Map[Int, Channel.End[MaterialLoad, Equipment.UnitSorterSignal]] = _
	private var inductListener: RUNNER = _

	lazy val endpointListener = dischargeListener orElse inductListener

	private def configurer: DomainConfigure[Equipment.UnitSorterSignal] = new DomainConfigure[Equipment.UnitSorterSignal] {
		override def configure(config: Equipment.UnitSorterSignal)(implicit ctx: SignallingContext[Equipment.UnitSorterSignal]): DomainMessageProcessor[Equipment.UnitSorterSignal] = {
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

	private def inductSink(manager: Ref[_ <: DomainSignal], chOps: Channel.Ops[MaterialLoad, _, Equipment.UnitSorterSignal], host: Ref[UnitSorterSignal]) =
		new Channel.Sink[MaterialLoad, Equipment.UnitSorterSignal] {
			lazy override val ref = host
			lazy val end = chOps.registerEnd(this)
			override def loadArrived(endpoint: Channel.End[MaterialLoad, Equipment.UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: SignallingContext[Equipment.UnitSorterSignal]): RUNNER = {
				//ctx.aCtx.log.info(s"Load Arrived: $load at ${endpoint.channelName}")
				ctx.signal(manager, LoadArrival(load, endpoint.channelName))
				stopCycle
				DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, Equipment.UnitSorterSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: SignallingContext[Equipment.UnitSorterSignal]): RUNNER = {
				DomainRun.same
			}
		}.end

	private def dischargeSource(manager: Ref[_ <: DomainSignal], chOps: Channel.Ops[MaterialLoad, Equipment.UnitSorterSignal, _], host: Ref[UnitSorterSignal]) =
		new Channel.Source[MaterialLoad, Equipment.UnitSorterSignal] {
			override lazy val ref = host
			lazy val start = chOps.registerStart(this)
			override def loadAcknowledged(ep: Channel.Start[MaterialLoad, Equipment.UnitSorterSignal], load: MaterialLoad)(implicit ctx: SignallingContext[Equipment.UnitSorterSignal]): RUNNER = {
				stopCycle
				DomainRun.same
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
