/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

//import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor.ProcessorMessage
import com.saldubatech.util.Lang.TBD

object Simulation extends App {
	//================================================== TO BE DEFINED =======================================
	//========================================================================================================

	// Message Taxonomy
	trait Message
	trait Notification extends Message
	trait Command extends Message

}

class Simulation {//{CompleteAction, RegisterTimeMonitor, Registered}

}
