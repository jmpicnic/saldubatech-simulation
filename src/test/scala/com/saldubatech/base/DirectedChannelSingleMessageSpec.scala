/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.DirectedChannel.{ConfigureStarts, ConfigureEnds}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActor, SimActorMixIn}
import com.saldubatech.test.utils.SpecActorHarness.KickOff
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.utils.Boxer._

import scala.languageFeature.postfixOps


class DirectedChannelSingleMessageSpec extends BaseActorSpec(ActorSystem("MaterialChannelUnidirectionalSpec")) {

	"A Directed Channel" when {

		val underTest = new DirectedChannel[Material](1, "underTest") {
			override def registerStart(owner: DirectedChannel.Destination[Material]): DirectedChannel.Start[Material] = {
				testActor ! "Registering Left"
				super.registerStart(owner)
			}

			override def registerEnd(owner: DirectedChannel.Destination[Material]): DirectedChannel.End[Material] = {
				testActor ! "Registering Right"
				super.registerEnd(owner)
			}
		}

		class MockDestination(name: String, isLeft: Boolean, driver: ActorRef, gw: Gateway)
			extends SimActor(name, gw)
				with  DirectedChannel.Destination[Material] {

			override def onAccept(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
				log.info(s"New Load Arrival ${load.uid} at $name")
				s"New Load Arrival ${load.uid}" ~> driver now tick
				via.doneWithLoad(load, tick)
			}

			override def onRestore(via: DirectedChannel.Start[Material], tick: Long): Unit = {
				s"Received Acknowledgement at $name" ~> driver now tick
			}


			override def process(from: ActorRef, at: Long): Processing =
				processingBuilder(from, at) orElse {
					case a: Any => log.info(s"Processing builder does not catch $a, See ${processingBuilder(from, at)}")
				}

			override def configure: Configuring =
				if(isLeft) channelStartConfiguring
				else channelEndConfiguring
		}


		val loadProbe = Material()
		val otherLoad = Material()
		def newLoadArrivalMsg(ld: Material) = s"New Load Arrival ${ld.uid}"
		val firstLoadMsg = newLoadArrivalMsg(loadProbe)
		val secondLoadMsg = newLoadArrivalMsg(otherLoad)

		// Protocol Definition from left point of view
		val kickOff: (SimActorMixIn, ActorRef, Long) => Unit = (host, from, at) => {
			underTest.start.sendLoad(loadProbe, at)
			specLog.debug("Sending Load through Left Endpoint")
		}
		def actions(observer: ActorRef) = Seq[(SimActorMixIn, ActorRef, Long) => Processing](
			(host, from, tick) => {case firstLoadMsg => observer ! firstLoadMsg},
			(host, from, tick) => {case "Received Acknowledgement at left" => observer ! "Completed First Transfer"},
		)
		val harnessProbe = TestProbe()
		val harness = gw.simActorOf(Props(new SpecActorHarness(kickOff, actions(harnessProbe.testActor),"harness", gw, testActor.?)),"harness")

		val leftDestination = gw.simActorOf(Props(new MockDestination("left", isLeft = true, harness, gw)), "left")
		val rightDestination = gw.simActorOf(Props(new MockDestination("right", isLeft = false, harness, gw)), "right")
		"A. configured with two destinations" should {
			"1. be called as part of the configuraiton of the endpoints" in {
				gw.configure(harness, None)
				gw.configure(leftDestination, ConfigureStarts(Seq(underTest)))
				gw.configure(rightDestination, ConfigureEnds(Seq(underTest)))
				expectMsgAllOf("Registering Left", "Registering Right")
			}
			"2. Configure the Left Endpoint as Sender" in {
				underTest.start.owner.asInstanceOf[Actor].self shouldBe leftDestination
				underTest.start.peerOwner.! shouldBe rightDestination
				underTest.start.isInstanceOf[DirectedChannel.Start[Material]] shouldBe true
			}
			"3. And configure the Right Endpoint as Receiver" in {
				underTest.end.owner.asInstanceOf[Actor].self shouldBe rightDestination
				underTest.end.peerOwner.! shouldBe leftDestination
				underTest.end.isInstanceOf[DirectedChannel.End[Material]] shouldBe true
			}
		}
		"B. asked to send a load from the sending side" should {
			"call the destination consumeInput when sent a load" in {
				gw.injectInitialAction(harness, KickOff())
				gw.activate()
				expectMsg(firstLoadMsg)
				harnessProbe.expectMsg(firstLoadMsg)
				expectMsg("Received Acknowledgement at left")
				harnessProbe.expectMsg("Completed First Transfer")
				expectNoMessage()
			}
		}
	}
}
