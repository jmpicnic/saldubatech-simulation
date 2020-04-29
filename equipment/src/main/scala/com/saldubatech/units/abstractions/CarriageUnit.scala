package com.saldubatech.units.abstractions

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.SlotLocator

object CarriageUnit {

	class LoadCmd(val loc: SlotLocator) extends Identification.Impl()

	class UnloadCmd(val loc: SlotLocator) extends Identification.Impl()

	class InductCmd[SinkProfile >: ChannelConnections.ChannelDestinationMessage](val from: Channel.End[MaterialLoad, SinkProfile], val at: SlotLocator) extends Identification.Impl()

	class DischargeCmd[SourceProfile >: ChannelConnections.ChannelSourceMessage](val to: Channel.Start[MaterialLoad, SourceProfile], val at: SlotLocator) extends Identification.Impl()

	sealed trait WaitForLoad
	sealed trait WaitForChannel

	def inductSink[HS >: ChannelConnections.ChannelSourceSink, H <: CarriageUnit[HS]]
	(host: H)(loadArrivalBehavior: (host.INDUCT, MaterialLoad, Option[Distance], host.CTX) => PartialFunction[WaitForLoad, host.RUNNER])
	(inboundSlot: SlotLocator, chOps: Channel.Ops[MaterialLoad, _, HS])=
		new EquipmentUnit.InductSink[HS](chOps, host.self) {
			override def loadArrived(endpoint: host.INDUCT, load: MaterialLoad, at: Option[Distance])(implicit ctx: host.CTX) = loadArrivalBehavior(endpoint, load, at, ctx)(host.waitingForLoad)

			override def loadReleased(endpoint: host.INDUCT, load: MaterialLoad, at: Option[Distance])(implicit ctx: host.CTX) = Processor.DomainRun.same
		}.end

	def dischargeSource[HS >: ChannelConnections.ChannelSourceSink, H <: CarriageUnit[HS]]
	(host: H)(slot: SlotLocator, manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, HS, _])
	(channelFreeBehavior: (host.DISCHARGE, MaterialLoad, host.CTX) => PartialFunction[WaitForChannel, host.RUNNER])=
		new EquipmentUnit.DischargeSource[HS](chOps, host.self) {
			override def loadAcknowledged(endpoint: host.DISCHARGE, load: MaterialLoad)(implicit ctx: host.CTX): host.RUNNER = channelFreeBehavior(endpoint, load, ctx)(host.waitingForChannel)
		}.start
}

trait CarriageUnit[HOST_SIGNAL >: ChannelConnections.ChannelSourceSink] extends EquipmentUnit[HOST_SIGNAL] {
	import CarriageUnit._


	protected case object NoLoadWait extends WaitForLoad
	protected case class WaitInducting(induct: INDUCT*) extends WaitForLoad
	protected case class WaitInductingToDischarge(discharge: DISCHARGE, toLoc: SlotLocator, induct: INDUCT*) extends WaitForLoad
	protected case class WaitInductingToStore(toLoc: SlotLocator, induct: INDUCT*) extends WaitForLoad
	private var _waitingForLoad: WaitForLoad = NoLoadWait
	def waitingForLoad: WaitForLoad = _waitingForLoad
	protected def waitInducting(induct: INDUCT*) = _waitingForLoad = WaitInducting(induct: _*)
	protected def waitInductingToDischarge(discharge: DISCHARGE, toLoc: SlotLocator, induct: INDUCT*) = _waitingForLoad = WaitInductingToDischarge(discharge, toLoc, induct: _*)
	protected def waitInductingToStore(toLoc: SlotLocator, induct: INDUCT*) = _waitingForLoad = WaitInductingToStore( toLoc, induct: _*)
	protected def endLoadWait = _waitingForLoad = NoLoadWait

	protected case object NoChannelWait extends WaitForChannel
	protected case class WaitDischarging(ch: DISCHARGE, loc: SlotLocator) extends WaitForChannel
	protected var _waitingForChannel: WaitForChannel = NoChannelWait
	def waitingForChannel: WaitForChannel = _waitingForChannel
	protected def waitDischarging(ch: DISCHARGE, loc: SlotLocator) = _waitingForChannel = WaitDischarging(ch, loc)
	protected def endChannelWait = _waitingForChannel = NoChannelWait

	type INDUCT = Channel.End[MaterialLoad, HOST_SIGNAL]
	type DISCHARGE = Channel.Start[MaterialLoad, HOST_SIGNAL]

	type LOAD_SIGNAL <: HOST_SIGNAL with LoadCmd
	def loader(loc: SlotLocator): LOAD_SIGNAL
	type UNLOAD_SIGNAL <: HOST_SIGNAL with UnloadCmd
	def unloader(loc: SlotLocator): UNLOAD_SIGNAL
	type INDUCT_SIGNAL <: HOST_SIGNAL with InductCmd[HOST_SIGNAL]
	def inducter(from: Channel.End[MaterialLoad, HOST_SIGNAL], at: SlotLocator): INDUCT_SIGNAL
	type DISCHARGE_SIGNAL <: HOST_SIGNAL with DischargeCmd[HOST_SIGNAL]
	def discharger(to: Channel.Start[MaterialLoad, HOST_SIGNAL], at: SlotLocator): DISCHARGE_SIGNAL


	protected def rejectExternalCommand(cmd: EXTERNAL_COMMAND, msg: String)(implicit ctx: CTX): RUNNER = {
		ctx.reply(notAcceptedNotification(cmd, msg))
		Processor.DomainRun.same
	}

	private var _currentCommand: Option[EXTERNAL_COMMAND] = None
	protected def currentCommand = _currentCommand
	protected def executeCommand(cmd: EXTERNAL_COMMAND)(body: => RUNNER)(implicit ctx: CTX): RUNNER =
		if(_currentCommand isEmpty) {
			_currentCommand = Some(cmd)
			body
		} else rejectExternalCommand(cmd, s"$name is busy")

	protected def completeCommand(next: => RUNNER = Processor.DomainRun.same,
	                              notifier: EXTERNAL_COMMAND => NOTIFICATION = completedCommandNotification)
	                             (implicit ctx: CTX): RUNNER = {
		assert(_currentCommand nonEmpty)
		_currentCommand.foreach(cmd => ctx.signal(manager, notifier(cmd)))
		doneCommand
		next
	}
	protected def doneCommand = _currentCommand = None

	protected def completedCommandNotification(cmd: EXTERNAL_COMMAND): NOTIFICATION
	protected def notAcceptedNotification(cmd: EXTERNAL_COMMAND, msg: String): NOTIFICATION
}