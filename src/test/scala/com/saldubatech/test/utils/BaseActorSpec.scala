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

	override def afterAll: Unit = {
		specLogger.info(s">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>Execute After ALL")
    gw.shutdown()
		_system.terminate()
  }

	override def beforeAll: Unit = {
		specLogger.info(s">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>Execute Before ALL")
		//val simActorRef: ActorRef = gw.simActorOf(SimTestProbeForwarder.props("HostForwarder", gw, testActor), "hostForwarder")
		//gw.configure(simActorRef)
  }
}