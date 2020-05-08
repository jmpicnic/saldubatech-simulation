/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

//import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import java.util.Comparator

import akka.actor.typed.ActorRef
import akka.dispatch.Envelope
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{ClockMessage, Enqueue, Tick}

object Simulation extends App {

	// Message Taxonomy

	/**
	 * Exchanged between elements of the simulation engine implementation to control its workings
	 *
	 */
	trait Signal extends Identification
	trait EngineSignal extends Signal

	/**
	 * Messages accepted by the Simulation Controller
	 */
	trait ControllerMessage extends Identification

	/**
	Exchanged between simulation agents, carrying domain messages
	 */
	type SimSignal = PSimSignal[_ <: DomainSignal]
	trait PSimSignal[+DS <: DomainSignal] extends Identification {
		val tick: Tick
		val from: ActorRef[_ <: SimSignal]
		def payload: DS
	}

	/**
	 * Supertype for all Domain Messages (payload of SimSignal
	 *
	 */
	trait DomainSignal extends Identification

	type SimRef[DS <: DomainSignal] = ActorRef[PSimSignal[DS]]

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
			case m: Enqueue[_] if m.to == m.act.from => MAX_PRIO - 10
			case m: Enqueue[_] => MAX_PRIO - 20
			case m: ClockMessage => MAX_PRIO
			case other => 0
		}
	}

}

class Simulation(name: String, startTime: Tick) {//{CompleteAction, RegisterTimeMonitor, Registered}

}
