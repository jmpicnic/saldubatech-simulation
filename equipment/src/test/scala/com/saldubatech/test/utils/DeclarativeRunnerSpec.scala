/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

//class ActorToTest(gw: Gateway) extends SimActor("To Test", gw)


class DeclarativeRunnerSpec(_system: ActorSystem)
  extends TestKit(_system)
    with DeclarativeUseCaseRunner
    with WordSpecLike
    with BeforeAndAfterAll {
  import DeclarativeUseCaseRunner._

  object MockUnderTest {
    def localProps(n: String, p: ActorRef): Props = Props(new MockUnderTest(n, p))
  }

  class MockUnderTest(name: String, peer: ActorRef)
    extends Actor {
    override def receive: Receive = {
      case "One M" =>
        peer ! "Two M"
      case "Three M" =>
        peer ! "Four M"
        peer ! "Another M"
    }
  }


  def this() = this(ActorSystem("ProtocolTester"))


  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    shutdown(system)
  }


  "A Protocol Use Case" should {
    "Follow the sequence declaratively with the run method" in {
      val underTest = system.actorOf(MockUnderTest.localProps("underTest", testActor))

      val steps = Seq(
        DoSend(underTest, "One M"),
        DoReceive(underTest, "Two M"),
        DoSend(underTest, "Three M"),
        DoReceive(underTest, "Four M"),
        DoReceive(underTest, "Another M")
      )

      run(steps)
    }
  }
}
