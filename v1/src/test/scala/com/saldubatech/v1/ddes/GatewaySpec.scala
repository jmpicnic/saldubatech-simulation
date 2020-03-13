/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all rights reserved
 */

package com.saldubatech.v1.ddes

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.testkit.{EventFilter, TestKit}
import com.saldubatech.v1.ddes.Epoch.Action
import com.saldubatech.v1.ddes.Gateway.Configure
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class GatewaySpec(_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with WordSpecLike
    with BeforeAndAfterAll {

	import GlobalClock._

	def this() = this(ActorSystem("SimActorSpec"))

	object underTest extends Gateway(system){
		def getClock: ActorRef = clock
		def getWatcher: ActorRef = watcher
	}
	class MockActor(gw: Gateway) extends Actor with ActorLogging {
		def receive: Receive = {
			case Configure(p) =>
				gw.completedConfiguration(self)
				testActor ! p
			case Registered(r, n) => testActor ! (r, n)
			case act: Action =>
				underTest.getClock ! StartActionOnReceive(act)
				testActor ! act
				underTest.getClock ! CompleteAction(act)
			case a: Any =>
				log.debug(s"Untyped message $a")
				testActor ! a
		}
	}

	var aref: ActorRef = _

	"A Simulation" should {
		var aref2: ActorRef = null
		"be able to create a SimActor" when {
			"A configured Props object is provided" in {
				EventFilter.debug(message = "Received new Actor: MockActor, pending: 1, advised: 0", occurrences = 1) intercept {
					aref = underTest.simActorOf(Props(new MockActor(underTest)), "MockActor")
					aref ! "probeMsg"
					expectMsg("probeMsg")
				}
			}
		}
		"be able to inject messages" when {
			"it stays in configuring state until all actors are configured" in {
				EventFilter.debug(message = "Received new Actor: MockActor2, pending: 2, advised: 0", occurrences = 1) intercept {
					aref2 = underTest.simActorOf(Props(new MockActor(underTest)), "MockActor2")
				}

				Await.result(underTest.isConfigurationComplete, 500 millis) shouldBe Gateway.SimulationState.CONFIGURING

				EventFilter.debug(message = "Received configuration confirmation from MockActor2, pending: 1, advised: 0", occurrences = 1) intercept {
					underTest.configure(aref2, "ConfigMessage Second")
					expectMsg("ConfigMessage Second")
				}

				Await.result(underTest.isConfigurationComplete, 500 millis) shouldBe Gateway.SimulationState.CONFIGURING

			}
			"the simulation has not started" in {
				EventFilter.debug(message = "GlobalClock now: 0 : Enqueuing An initial message at 0 while stopped", occurrences = 1) intercept {
					underTest.injectInitialAction(aref, "An initial message")
				}
				expectNoMessage(500 millis)
				EventFilter.debug(message = "GlobalClock now: 0 : Enqueuing A second initial message at 10 while stopped", occurrences = 1) intercept {
					underTest.injectInitialAction(aref2, "A second initial message", 10)
				}
				expectNoMessage(500 millis)
			}
			"And be configured once it is sent a Configure message to the actor with the provided payload" in {
				EventFilter.debug(message = "Received configuration confirmation from MockActor, pending: 0, advised: 0", occurrences = 1) intercept {
					underTest.configure(aref, "ConfigurePayload")
					expectMsg("ConfigurePayload")
				}
				Await.result(underTest.isConfigurationComplete, 500 millis) shouldBe Gateway.SimulationState.READY

			}
		}
		"allow the simulation to start" when {
			"all actors have been configured" in {
				Await.result(underTest.isConfigurationComplete, 500 millis) shouldBe Gateway.SimulationState.READY
				underTest.activate()
				expectMsgAllOf(Action(underTest.getWatcher, aref, "An initial message", 0),
					Action(underTest.getWatcher, aref2, "A second initial message", 10))
			}
		}
	}
	it should {
		"Acknowledge when sent an observer to its internal clock" in {
			underTest.observeTime(aref)
			expectMsg((aref, 2)) // The first one is the Simulation internal simulation watcher
		}
	}

}
