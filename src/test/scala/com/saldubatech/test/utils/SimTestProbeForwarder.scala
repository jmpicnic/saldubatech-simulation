/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.ddes.{Gateway, SimActor}

object SimTestProbeForwarder {
	def props(name: String, gw: Gateway, testProbe: ActorRef,
                            assertion: Any => Boolean = (x: Any) => true) =
		Props(new SimTestProbeForwarder(name, gw, testProbe, assertion){
			def delegateProcessing2(from: ActorRef, at: Long): Processing = {case _ => }
		})


}

abstract class SimTestProbeForwarder(name: String, gw: Gateway, testProbe: ActorRef,
                            assertion: Any => Boolean = (x: Any) => true) extends SimActor(name,gw) {

	protected def delegateConfiguring: Configuring = {case a: Any => log.warning(s"Using default delegate configuring for $a")}

	private def forwardingConfiguring: Configuring = {
		case msg: Any =>
			log.debug(s"Configure: Forwarding message to ${testProbe.path.name}: <$msg>")
			testProbe ! msg
	}

	protected def forward(msg: Any): Unit = testProbe ! msg
	protected def delegate(from: ActorRef, at: Long, msg: Any): Unit = {
		log.warning(s"Using default delegate processing for $msg")
	}

	override def process(from: ActorRef, at: Long): Processing = {
		case msg: Any =>
			assert(assertion(msg), "Check of message assertion: "+assertion.toString)
			log.debug(s"Forwarder: delegating message to implementer: <$msg>")
			delegate(from, at, msg)
			log.debug(s"Forwarder: Forwarding message to ${testProbe.path.name}: <$msg>")
			forward(msg)
	}
	override def configure: Configuring =  {
		case msg: Any =>
			delegateConfiguring.apply(msg)
			forwardingConfiguring.apply(msg)
	}
}
