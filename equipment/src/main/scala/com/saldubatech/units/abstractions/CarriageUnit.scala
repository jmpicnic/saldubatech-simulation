package com.saldubatech.units.abstractions

import com.saldubatech.ddes.AgentTemplate.{DomainRun, FullSignallingContext}
import com.saldubatech.ddes.Simulation.DomainSignal
import com.saldubatech.protocols.Equipment

object CarriageUnit {



}

trait CarriageUnit[HOST_SIGNAL >: Equipment.ChannelSignal <: DomainSignal] extends EquipmentUnit[HOST_SIGNAL] {

	protected def rejectExternalCommand(cmd: EXTERNAL_COMMAND, msg: String)(implicit ctx: CTX): RUNNER = {
		ctx.signal(manager, notAcceptedNotification(cmd, msg))
		DomainRun.same
	}

	private var _currentCommand: Option[EXTERNAL_COMMAND] = None

	protected def currentCommand = _currentCommand

	protected def executeCommand(cmd: EXTERNAL_COMMAND)(body: => RUNNER)(implicit ctx: CTX): RUNNER =
		if (_currentCommand isEmpty) {
			_currentCommand = Some(cmd)
			body
		} else rejectExternalCommand(cmd, s"$name is busy")

	protected def completeCommand(next: => RUNNER = DomainRun.same,
	                              notifier: EXTERNAL_COMMAND => MANAGER_SIGNAL = completedCommandNotification)
	                                                (implicit ctx: CTX): RUNNER = {
	                                                //(implicit ctx: FullSignallingContect[HOST_SIGNAL, _ >: manager.type]): RUNNER = {	                                                (implicit ctx: FullSignallingContect[HOST_SIGNAL, _ >: manager.type]): RUNNER = {
		assert(_currentCommand nonEmpty)
		_currentCommand.foreach(cmd => ctx.signal(manager, notifier(cmd)))
		doneCommand
		next
	}

	protected def doneCommand = _currentCommand = None

	protected def completedCommandNotification(cmd: EXTERNAL_COMMAND): NOTIFICATION

	protected def notAcceptedNotification(cmd: EXTERNAL_COMMAND, msg: String): NOTIFICATION
}