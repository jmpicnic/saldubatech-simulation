/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

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

	var beforeCount = 0
	var afterCount = 0

	override def afterAll: Unit = {
		afterCount += 1
		specLogger.info(s">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>Execute After ALL: $afterCount")
    gw.shutdown()
		_system.terminate()
  }

	override def beforeAll: Unit = {
		beforeCount += 1
		specLogger.info(s">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>Execute Before ALL: $beforeCount")
		val simActorRef: ActorRef = gw.simActorOf(SimTestProbeForwarder.props("HostForwarder", gw, testActor), "hostForwarder")
		gw.configure(simActorRef)
  }
}