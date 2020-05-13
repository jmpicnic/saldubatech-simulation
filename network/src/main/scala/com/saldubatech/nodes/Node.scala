package com.saldubatech.nodes

import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}

abstract class Node[HOST_SIGNAL <: DomainSignal, SELF <: Node[HOST_SIGNAL, SELF]](override val name: String)
	extends Identification.Impl(name) with AgentTemplate[HOST_SIGNAL, SELF] {

	private var _processor: SimRef[_ <: DomainSignal] = null
	protected lazy val processor = _processor
	protected def installProcessor(proc: SimRef[_ <: DomainSignal]) = _processor = proc

}
