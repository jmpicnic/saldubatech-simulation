package com.saldubatech.units.carriage

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.CommandContext
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{OnRight, SlotLocator}

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

}
class CarriageComponent[HS >: ChannelConnections.ChannelSourceSink, HOST <: Host[HS]]
(travelPhysics: CarriageTravel, val host: HOST) {
	import CarriageComponent._

	def atLocation(loc: Int): CarriageComponent[HS, HOST] = {
		_currentLocation = loc
		this
	}
	def withInventory(inv: Map[SlotLocator, MaterialLoad]): CarriageComponent[HS, HOST] = {
		contents ++= inv
		this
	}

	def currentLocation = _currentLocation
	private var _currentLocation: Int = 0
	private var tray: Option[MaterialLoad] = None
	def inspect(loc: SlotLocator) = contents.get(loc)
	private val contents = mutable.Map.empty[SlotLocator, MaterialLoad]

	def loadFrom(loc: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome = {
//		println(s"### Traveling and Loading from $currentLocation to $loc within ${travelPhysics.timeToPickup(At(currentLocation), loc)} ticks")
		ctx.signalSelf(host.loader(loc), travelPhysics.timeToPickup(At(_currentLocation), loc))
		OperationOutcome.InTransit
	}

	def LOADING(continuation: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.LOAD_SIGNAL => continuation(ctx)(trayLoadingEffect(cmd.loc))
		}
	}

	private def trayLoadingEffect(loc: SlotLocator): LoadOperationOutcome = {
		_currentLocation = loc.idx
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
//		println(s"### Traveling and Unloading from $currentLocation to $loc within ${travelPhysics.timeToDeliver(At(currentLocation), loc)} ticks")
		ctx.signalSelf(host.unloader(loc), travelPhysics.timeToDeliver(At(_currentLocation), loc))
		OperationOutcome.InTransit
	}
	def UNLOADING(continuation: host.CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.UNLOAD_SIGNAL => continuation(ctx)(trayUnloadingEffect(cmd.loc))
		}
	}

	private def trayUnloadingEffect(loc: SlotLocator): UnloadOperationOutcome = {
		_currentLocation = loc.idx
		(contents.get(loc), tray) match {
			case (_, None) => UnloadOperationOutcome.ErrorTrayEmpty
			case (Some(_), _) => UnloadOperationOutcome.ErrorTargetFull
			case (None, Some(ld)) =>
				contents += loc -> ld
				tray = None
				UnloadOperationOutcome.Unloaded
		}
	}

	def inductFrom(from: host.INDUCT, at: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome =  {
//		println(s"### Traveling and Inducting from $currentLocation to $at within ${travelPhysics.timeToPickup(At(currentLocation), at)} ticks")
		ctx.signalSelf(host.inducter(from, at), travelPhysics.timeToPickup(At(_currentLocation), at))
		OperationOutcome.InTransit
	}

	def INDUCTING(continuation: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.INDUCT_SIGNAL => continuation(ctx)(trayInductEffect(cmd.from, cmd.at))
		}
	}

	private def trayInductEffect(from: host.INDUCT, at: SlotLocator)(implicit ctx: host.CTX): LoadOperationOutcome = {
		_currentLocation = at.idx
		(from.peekNext, tray) match {
			case (_, Some(_)) => LoadOperationOutcome.ErrorTrayFull
			case (None, _) => LoadOperationOutcome.ErrorTargetEmpty
			case (ldo@Some(_), None) =>
				tray = ldo.map(_._1)
				from.getNext
				LoadOperationOutcome.Loaded
		}
	}

	def dischargeTo(to: host.DISCHARGE, at: SlotLocator)(implicit ctx: host.CTX): UnloadOperationOutcome = {
//		println(s"### Traveling and Discharging from $currentLocation to $at within ${travelPhysics.timeToDeliver(At(currentLocation), at)} ticks")
		ctx.signalSelf(host.discharger(to, at), travelPhysics.timeToDeliver(At(_currentLocation), at))
		OperationOutcome.InTransit
	}

	def DISCHARGING(continuation: host.CTX => PartialFunction[UnloadOperationOutcome, host.RUNNER]): host.RUNNER = {
		implicit ctx: host.CTX => {
			case cmd: host.DISCHARGE_SIGNAL => continuation(ctx)(trayDischargeEffect(cmd.to, cmd.at))
		}
	}
	private def trayDischargeEffect(to: host.DISCHARGE, at: SlotLocator)(implicit ctx: host.CTX): UnloadOperationOutcome = {
		_currentLocation = at.idx
		tray match {
			case None => UnloadOperationOutcome.ErrorTrayEmpty
			case Some(ld) =>
				if(to.send(ld)) {
					tray = None
					UnloadOperationOutcome.Unloaded
				}	else UnloadOperationOutcome.ErrorTargetFull
		}
	}
}
