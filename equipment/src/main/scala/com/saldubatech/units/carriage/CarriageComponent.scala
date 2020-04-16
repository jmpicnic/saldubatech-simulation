package com.saldubatech.units.carriage

import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.CommandContext
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.Carriage.{OnRight, SlotLocator}

import scala.collection.mutable

object CarriageComponent {
	sealed trait OperationOutcome
	object OperationOutcome {
		case object InTransit extends LoadOperationOutcome with UnloadOperationOutcome
	}
	sealed trait LoadOperationOutcome extends OperationOutcome
	object LoadOperationOutcome {
		case object Loaded extends LoadOperationOutcome
		case object ErrorTargetEmpty extends LoadOperationOutcome
		case object ErrorTrayFull extends LoadOperationOutcome
	}

	sealed trait UnloadOperationOutcome extends OperationOutcome
	object UnloadOperationOutcome {
		case object Unloaded extends UnloadOperationOutcome
		case object ErrorTargetFull extends UnloadOperationOutcome
		case object ErrorTrayEmpty extends UnloadOperationOutcome
	}

	class LoadCmd(val loc: SlotLocator)
	class UnloadCmd(val loc: SlotLocator)
	class InductCmd[SinkProfile >: ChannelConnections.ChannelDestinationMessage](val from: Channel.End[MaterialLoad, SinkProfile], val at: SlotLocator)
	class DischargeCmd[SourceProfile >: ChannelConnections.ChannelSourceMessage](val to: Channel.Start[MaterialLoad, SourceProfile], val at: SlotLocator)

	trait Host[SourceProfile >: ChannelConnections.ChannelSourceMessage,
		SinkProfile >: ChannelConnections.ChannelDestinationMessage] {
		type HOST_SIGNAL <: SourceProfile with SinkProfile
		type CTX = Processor.SignallingContext[HOST_SIGNAL]
		type RUNNER = Processor.DomainRun[HOST_SIGNAL]
		final val sourceCtx: CTX => Processor.SignallingContext[SourceProfile] = ctx => CommandContext(ctx.from, ctx.now, ctx.aCtx)(ctx.clock)
		final val sinkCtx: CTX => Processor.SignallingContext[SinkProfile] = ctx => CommandContext(ctx.from, ctx.now, ctx.aCtx)(ctx.clock)




/*
		def loaded(from: SlotLocator, load: MaterialLoad, at: Tick)
		def unloaded(to: SlotLocator, load: MaterialLoad, at: Tick)
		def inducted(from: Channel.End[MaterialLoad, SinkProfile], load: MaterialLoad, at: Tick)
		def discharged(to: Channel.Start[MaterialLoad, SourceProfile], load: MaterialLoad, at: Tick)
*/
		type LOAD_SIGNAL <: HOST_SIGNAL with LoadCmd
		def loader(loc: SlotLocator): LOAD_SIGNAL
		type UNLOAD_SIGNAL <: HOST_SIGNAL with UnloadCmd
		def unloader(loc: SlotLocator): UNLOAD_SIGNAL
		type INDUCT_SIGNAL <: HOST_SIGNAL with InductCmd[SinkProfile]
		def inducter(from: Channel.End[MaterialLoad, SinkProfile], at: SlotLocator): INDUCT_SIGNAL
		type DISCHARGE_SIGNAL <: HOST_SIGNAL with DischargeCmd[SourceProfile]
		def discharger(to: Channel.Start[MaterialLoad, SourceProfile], at: SlotLocator): DISCHARGE_SIGNAL
	}

}
class CarriageComponent[SourceProfile >: ChannelConnections.ChannelSourceMessage,
	SinkProfile >: ChannelConnections.ChannelDestinationMessage, HOST <: CarriageComponent.Host[SourceProfile, SinkProfile]](travelPhysics: Carriage.CarriageTravel, val host: HOST) {
	import CarriageComponent._

	def configureInitialLocation(loc: Int) = currentLocation = loc
	def configureInitialInventory(inv: Map[SlotLocator, MaterialLoad]) = contents ++= inv

	private var currentLocation: Int = 0
	private var tray: Option[MaterialLoad] = None
	private val contents = mutable.Map.empty[SlotLocator, MaterialLoad]

	def loadFrom(loc: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome = {
		ctx.signalSelf(host.loader(loc), travelPhysics.timeToPickup(Carriage.At(currentLocation), loc))
		OperationOutcome.InTransit
	}

	def LOADING(continuation: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.LOAD_SIGNAL => continuation(ctx)(trayLoadingEffect(cmd.loc))
		}
	}

	private def trayLoadingEffect(loc: SlotLocator): LoadOperationOutcome = {
		currentLocation = loc.idx
		(contents.get(loc), tray) match {
			case (_, Some(_)) => LoadOperationOutcome.ErrorTrayFull
			case (None, _) => LoadOperationOutcome.ErrorTargetEmpty
			case (ldo@Some(_), None) =>
				tray = ldo
				contents -= loc
				LoadOperationOutcome.Loaded
		}
	}

	def unloadTo(loc: SlotLocator)(implicit ctx: host.CTX): UnloadOperationOutcome = {
		ctx.signalSelf(host.unloader(loc), travelPhysics.timeToDeliver(Carriage.At(currentLocation), loc))
		OperationOutcome.InTransit
	}
	def UNLOADING(continuation: host.CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.UNLOAD_SIGNAL => continuation(ctx)(trayUnloadingEffect(cmd.loc))
		}
	}

	private def trayUnloadingEffect(loc: SlotLocator): UnloadOperationOutcome = {
		currentLocation = loc.idx
		(contents.get(loc), tray) match {
			case (_, None) => UnloadOperationOutcome.ErrorTrayEmpty
			case (Some(_), _) => UnloadOperationOutcome.ErrorTargetFull
			case (None, Some(ld)) =>
				contents += loc -> ld
				tray = None
				UnloadOperationOutcome.Unloaded
		}
	}

	def inductFrom(from: Channel.End[MaterialLoad, SinkProfile], at: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome =  {
		println(s"Traveling from $currentLocation to $at within ${travelPhysics.timeToPickup(Carriage.At(currentLocation), at)} ticks")
		ctx.signalSelf(host.inducter(from, at), travelPhysics.timeToPickup(Carriage.At(currentLocation), at))
		OperationOutcome.InTransit
	}

	def INDUCTING(continuation: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.INDUCT_SIGNAL => continuation(ctx)(trayInductEffect(cmd.from, cmd.at))
		}
	}

	private def trayInductEffect(from: Channel.End[MaterialLoad, SinkProfile], at: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome = {
		currentLocation = at.idx
		(from.peekNext, tray) match {
			case (_, Some(_)) => LoadOperationOutcome.ErrorTrayFull
			case (None, _) => LoadOperationOutcome.ErrorTargetEmpty
			case (ldo@Some(_), None) =>
				tray = ldo.map(_._1)
				from.getNext(host.sinkCtx(ctx))
				LoadOperationOutcome.Loaded
		}
	}

	def dischargeTo(to: Channel.Start[MaterialLoad, SourceProfile], at: SlotLocator)(implicit ctx: host.CTX): UnloadOperationOutcome = {
		ctx.signalSelf(host.discharger(to, at), travelPhysics.timeToDeliver(Carriage.At(currentLocation), at))
		OperationOutcome.InTransit
	}

	def DISCHARGING(continuation: host.CTX => PartialFunction[UnloadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.DISCHARGE_SIGNAL => continuation(ctx)(trayDischargeEffect(cmd.to, cmd.at))
		}
	}
	private def trayDischargeEffect(to: Channel.Start[MaterialLoad, SourceProfile], at: Carriage.SlotLocator)(implicit ctx: host.CTX): UnloadOperationOutcome = {
		currentLocation = at.idx
		tray match {
			case None => UnloadOperationOutcome.ErrorTrayEmpty
			case Some(ld) =>
				if(to.send(ld)(host.sourceCtx(ctx))) {
					tray = None
					UnloadOperationOutcome.Unloaded
				}	else UnloadOperationOutcome.ErrorTargetFull
		}
	}
}
