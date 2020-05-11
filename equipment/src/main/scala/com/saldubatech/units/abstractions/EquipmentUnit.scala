package com.saldubatech.units.abstractions


import com.saldubatech.ddes.AgentTemplate.{DomainRun, FullSignallingContext}
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.protocols.EquipmentManagement

object EquipmentUnit {

		def nopRunner[Signal <: DomainSignal]: DomainRun[Signal] = (ctx:  FullSignallingContext[Signal, _]) => {
			case n: Any if false => DomainRun.same
		}
}

trait EquipmentUnit[EQ_SIGNAL <: DomainSignal] {
	type HOST <: EquipmentUnit[EQ_SIGNAL]
	type EXTERNAL_COMMAND <: EQ_SIGNAL
	type NOTIFICATION <: EquipmentManagement.EquipmentNotification

	type MANAGER_SIGNAL >: NOTIFICATION <: DomainSignal

	lazy val self: SimRef[EQ_SIGNAL]  = _self
	private var _self: SimRef[EQ_SIGNAL] = null
	def installSelf(s: SimRef[EQ_SIGNAL]) = _self = s
	val name: String
	private var _manager: SimRef[MANAGER_SIGNAL] = _
	lazy val manager: SimRef[MANAGER_SIGNAL] = _manager
	def installManager(m: SimRef[_ <: DomainSignal]) = m match {
		case ms :SimRef[MANAGER_SIGNAL] => _manager = ms
		case other => throw new RuntimeException(s"Illegal Manager Type for $other")
	}

	type CTX = FullSignallingContext[EQ_SIGNAL, _ <: DomainSignal]
	type RUNNER = DomainRun[EQ_SIGNAL]

}
