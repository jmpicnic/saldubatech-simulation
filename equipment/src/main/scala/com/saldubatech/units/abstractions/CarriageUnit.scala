package com.saldubatech.units.abstractions

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor
import com.saldubatech.physics.Travel.Distance
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.SlotLocator

object CarriageUnit {



}

trait CarriageUnit[HOST_SIGNAL >: ChannelConnections.ChannelSourceSink] extends EquipmentUnit[HOST_SIGNAL] {
	import CarriageUnit._

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