/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.ddes

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{EventFilter, TestKit}
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimActor.Processing
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._




class SimActorImplSpec(_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with WordSpecLike
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("SimActorSpec"))
  /*
  implicit val system = ActorSystem("testsystem", ConfigFactory.parseString("""
  akka.loggers = ["akka.testkit.TestEventListener"]
  """))
   */

  object gw extends Gateway(system){
		def getClock: ActorRef = clock
		def getWatcher: ActorRef = watcher
	}

  class MockSimActorImpl(name: String, _gw: gw.type, target: ActorRef) extends SimActorImpl(name, _gw) {
    override def process(from: ActorRef, at: Long): Processing = {
      case action: Any =>
        target ! action
    }
    override def configure: Configuring = {
      case a: Any =>
        log.debug(s"Configure action: $a")
        target ! a
    }
  }


  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    gw.shutdown()//shutdown(system)
  }

  var underTest: ActorRef = _

  "A SimActor" should {

    object MockSimActor {
      def props(name: String, _gw: gw.type, target: ActorRef): Props = Props(new MockSimActorImpl(name, _gw, target))
    }
    "Be registered for configuration" when {
      "created by the gateway" should {
        "register itself to be configured" in {
          EventFilter.debug(message = "Received new Actor: underTest, pending: 1, advised: 0", occurrences = 1) intercept {
            underTest = gw.simActorOf(MockSimActor.props("underTest", gw, testActor), "underTest")
          }
        }
        "acknowledge configuration to the gateway when configured" in {
          EventFilter.debug(message = "Configure action: ConfigMessage", occurrences = 1) intercept {
            gw.configure(underTest, "ConfigMessage")
            expectMsg("ConfigMessage")
          }
        }
      }
    }
  }
  it should {
    "do nothing" when {
      "injected messages before starting the simulation" in {
        gw.injectInitialAction(underTest, "A probe message", 220)
        expectNoMessage(500 millis)
      }
    }
    "process the pending messages" when {
      "the simulation is started" in {
        EventFilter.debug(message="NO MORE WORK TO DO at 220") intercept {
          gw.activate()
          expectMsg("A probe message")
        }
      }
    }
  }


}