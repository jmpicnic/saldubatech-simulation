package com.saldubatech.units.abstractions


import com.saldubatech.ddes.AgentTemplate.{DomainRun, Ref, SignallingContext}
import com.saldubatech.ddes.Simulation.DomainSignal
import com.saldubatech.protocols.EquipmentManagement

object EquipmentUnit {

		def nopRunner[Signal <: DomainSignal]: DomainRun[Signal] = (ctx: SignallingContext[Signal]) => {
			case n: Any if false => DomainRun.same
		}
}

trait EquipmentUnit[EQ_SIGNAL <: DomainSignal] {

	lazy val self: Ref[EQ_SIGNAL]  = _self
	private var _self: Ref[EQ_SIGNAL] = null
	def installSelf(s: Ref[EQ_SIGNAL]) = _self = s
	val name: String
	private var _manager: Ref[_ <: DomainSignal] = _
	protected lazy val manager: Ref[_ <: DomainSignal] = _manager
	def installManager(m: Ref[_ <: DomainSignal]) = _manager = m

	type HOST <: EquipmentUnit[EQ_SIGNAL]
	type EXTERNAL_COMMAND <: EQ_SIGNAL
	type NOTIFICATION <: EquipmentManagement.EquipmentNotification

	type CTX = SignallingContext[EQ_SIGNAL]
	type RUNNER = DomainRun[EQ_SIGNAL]

}
