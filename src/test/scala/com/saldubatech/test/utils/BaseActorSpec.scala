/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import com.saldubatech.ddes.Gateway
import com.saldubatech.events.EventSpooler
import com.typesafe.scalalogging.Logger


class BaseActorSpec(_system: ActorSystem, val spooler: Option[EventSpooler] = None)
  extends TestKit(_system)
    with BaseSpec {

	val specLog = Logger("com.saldubatech.SpecLogger")
  implicit object gw extends Gateway(system){
		def getClock: ActorRef = clock
		def getWatcher: ActorRef = watcher
	}
	type TestGateway = gw.type

  val simActorRef: ActorRef = gw.simActorOf(SimTestProbeForwarder.props("HostForwarder", gw, testActor), "hostForwarder")


	override def afterAll: Unit = {
    gw.shutdown()
  }

	override def beforeAll: Unit = {
		gw.configure(simActorRef)
  }
}