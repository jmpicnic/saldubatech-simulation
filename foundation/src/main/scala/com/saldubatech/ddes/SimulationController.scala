package com.saldubatech.ddes

import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Simulation.ControllerMessage

object SimulationController {
	type Ref = ActorRef[ControllerMessage]

}
