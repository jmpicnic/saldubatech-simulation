package com.saldubatech.units.abstractions


import com.saldubatech.ddes.AgentTemplate.{DomainRun, SignallingContext}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.protocols.EquipmentManagement

object EquipmentUnit {

		def nopRunner[Signal <: DomainSignal]: DomainRun[Signal] = (ctx: SignallingContext[Signal]) => {
			case n: Any if false => DomainRun.same
		}
}

trait EquipmentUnit[EQ_SIGNAL <: DomainSignal] {

	lazy val self: SimRef = _self
	private var _self: SimRef = null
	def installSelf(s: SimRef) = _self = s
	val name: String
	private var _manager: SimRef = _
	protected lazy val manager: SimRef = _manager
	def installManager(m: SimRef) = _manager = m

	type HOST <: EquipmentUnit[EQ_SIGNAL]
	type EXTERNAL_COMMAND <: EQ_SIGNAL
	type NOTIFICATION <: EquipmentManagement.EquipmentNotification

	type CTX = SignallingContext[EQ_SIGNAL]
	type RUNNER = DomainRun[EQ_SIGNAL]

}
