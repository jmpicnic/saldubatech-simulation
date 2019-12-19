/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.saldubatech.ddes.Gateway


class TestTapProbe(val name: String, gw: Gateway, monitor: ActorRef, target: ActorRef,
                   assertion: Any => Boolean = (x: Any) => true) extends Actor with ActorLogging {
	override def receive: Receive = {
		case msg: Any =>
			assert(assertion(msg), "Check of message assertion: "+assertion.toString)
			log.debug("Forwarding message to {}: <{}>", target.path.name, msg)
			target ! msg
			monitor ! msg
	}
}
