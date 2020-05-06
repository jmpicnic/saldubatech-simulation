package com.saldubatech.units.abstractions


import com.saldubatech.ddes.Processor
import com.saldubatech.protocols.EquipmentManagement

object EquipmentUnit {

		def nopRunner[Signal]: Processor.DomainRun[Signal] = (ctx: Processor.SignallingContext[Signal]) => {
			case n: Any if false => Processor.DomainRun.same
		}
}

trait EquipmentUnit[EQ_SIGNAL] {

	lazy val self: Processor.Ref = _self
	private var _self: Processor.Ref = null
	def installSelf(s: Processor.Ref) = _self = s
	val name: String
	private var _manager: Processor.Ref = _
	protected lazy val manager: Processor.Ref = _manager
	def installManager(m: Processor.Ref) = _manager = m

	type HOST <: EquipmentUnit[EQ_SIGNAL]
	type EXTERNAL_COMMAND <: EQ_SIGNAL
	type NOTIFICATION <: EquipmentManagement.EquipmentNotification

	type CTX = Processor.SignallingContext[EQ_SIGNAL]
	type RUNNER = Processor.DomainRun[EQ_SIGNAL]

}
