/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

//import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import java.util.Comparator

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.dispatch.Envelope
import com.saldubatech.ddes.Clock.{ClockAction, ClockMessage, ClockRef, Enqueue, Tick}
import com.saldubatech.ddes.Processor.{ProcessorBehavior, ProcessorMessage, ProcessorRef}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.util.Lang.TBD

object Simulation extends App {

	//================================================== TO BE DEFINED =======================================
	//========================================================================================================

	// Message Taxonomy
	trait Message

	trait Notification extends Message

	trait Command extends Message

	type ControllerRef = ActorRef[ControllerMessage]

	/*
	Getting it ready to use UnboundedStablePriorityMailbox to ensure that self messages have higher priority.

	Priorities:
	1. Clock Timekeeping and Management messasges
	2. Clock Enqueue Messages "self addressed"
	3. Clock Enqueue Messages "other addressed"
	4. Other messages
	 */
	object MessagePrioritizer extends Comparator[Envelope] {
		override def compare(o1: Envelope, o2: Envelope): Int = msgPrio(o1) - msgPrio(o2)

		private val MAX_PRIO = 100

		private def msgPrio(e: Envelope): Int = e.message match {
			case m: Enqueue if m.to == m.act.from => MAX_PRIO - 10
			case m: Enqueue => MAX_PRIO - 20
			case m: ClockMessage => MAX_PRIO
			case other => 0
		}
	}

}

class Simulation(name: String, startTime: Tick) {//{CompleteAction, RegisterTimeMonitor, Registered}
	import Simulation._

}
