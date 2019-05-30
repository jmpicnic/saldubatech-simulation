/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.utils.Boxer._
import com.saldubatech.base._
import com.saldubatech.base.channels.v1.{AbstractChannel, ReversibleChannel}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.{Gateway, SimActor, SimActorImpl}
import com.saldubatech.test.utils.SpecActorHarness.KickOff
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.ddes.SimDSL._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class ReversibleChannelSpec extends BaseActorSpec(ActorSystem("MaterialChannelUnidirectionalSpec")) {

	"A Reversible Channel" when {

		object underTest extends ReversibleChannel[Material](1, "underTest") {
			var leftEP: ReversibleChannel.Endpoint[Material] = _
			var rightEP: ReversibleChannel.Endpoint[Material] = _

			override def registerLeft(owner: AbstractChannel.Destination[Material, ReversibleChannel.Endpoint[Material]]): ReversibleChannel.Endpoint[Material] = {
				leftEP = super.registerLeft(owner)
				testActor ! "Registering Left"
				leftEP
			}

			override def registerRight(owner: AbstractChannel.Destination[Material, ReversibleChannel.Endpoint[Material]]): ReversibleChannel.Endpoint[Material] = {
				rightEP = super.registerRight(owner)
				testActor ! "Registering Right"
				rightEP
			}
		}

		class MockDestination(val name: String, isLeft: Boolean, driver: ActorRef, gw: Gateway)
			extends SimActorImpl(name, gw)
				with  ReversibleChannel.Destination[Material] {
			override def onAccept(via: ReversibleChannel.Endpoint[Material], load: Material, tick: Long): Unit = {
				log.info(s"New Load Arrival ${load.uid} at $name")
				s"New Load Arrival ${load.uid}" ~> driver now tick
				via.doneWithLoad(load, tick)
			}

			override def onRestore(via: ReversibleChannel.Endpoint[Material], tick: Long): Unit = {
				s"Received Acknowledgement at $name" ~> driver now tick
			}


			override def process(from: ActorRef, at: Long): Processing =
				processingBuilder(from, at).orElse(
					if(isLeft)
						underTest.leftEP.processAllowSwitching(from, at) orElse underTest.leftEP.processSwitchRequest(from, at)
					else
						underTest.rightEP.processAllowSwitching(from, at) orElse underTest.rightEP.processSwitchRequest(from, at)
				)

			override def configure: Configuring =
				if(isLeft) channelLeftConfiguring
				else channelRightConfiguring
		}


		val loadProbe = Material()
		val otherLoad = Material()
		def newLoadArrivalMsg(ld: Material) = s"New Load Arrival ${ld.uid}"
		val firstLoadMsg = newLoadArrivalMsg(loadProbe)
		val secondLoadMsg = newLoadArrivalMsg(otherLoad)

		// Protocol Definition from left point of view
		val kickOff: (SimActor, ActorRef, Long) => Unit = (host, from, at) => {
			underTest.leftEP.sendLoad(loadProbe, at)
			specLog.debug("Sending Load through Left Endpoint")
		}
		def actions(observer: ActorRef) = Seq[(SimActor, ActorRef, Long) => Processing](
			(host, from, tick) => {case s: String if s == firstLoadMsg => observer ! firstLoadMsg},
			(host, from, tick) => {case "Received Acknowledgement at left" => observer ! "Completed First Transfer"; underTest.rightEP.requestSendingRole(tick)},
			(host, from, tick) => {
				case "Received Acknowledgement at right" =>
					underTest.leftEP.sendLoad(otherLoad, tick) shouldBe false
					observer ! s"Completed Switch ${underTest.rightEP.sendLoad(otherLoad, tick)}"},
			(host, from, tick) => {case s: String if s == secondLoadMsg => observer ! secondLoadMsg},
			(host, from, tick) => {case "Received Acknowledgement at right" => observer ! s"Reverse Load acknowledged $otherLoad"}
		)
		val harnessProbe = TestProbe()
		val harness = gw.simActorOf(Props(new SpecActorHarness(kickOff, actions(harnessProbe.testActor),"harness", gw, testActor.?)),"harness")

		val leftDestination = gw.simActorOf(Props(new MockDestination("left", isLeft = true, harness, gw)), "left")
		val rightDestination = gw.simActorOf(Props(new MockDestination("right", isLeft = false, harness, gw)), "right")
		"A. configured with two destinations" should {
			"1. be called as part of the configuraiton of the endpoints" in {
				gw.configure(harness, None)
				gw.configure(leftDestination, ConfigureLeftEndpoints(Seq(underTest)))
				gw.configure(rightDestination, ConfigureRightEndpoints(Seq(underTest)))
				expectMsgAllOf("Registering Left", "Registering Right")
			}
			"2. Configure the Left Endpoint as Sender" in {
				underTest.leftEP.owner.asInstanceOf[Actor].self shouldBe leftDestination
				underTest.leftEP.peerOwner.! shouldBe rightDestination
				underTest.leftEP.isInstanceOf[ReversibleChannel.Endpoint[Material]] shouldBe true
				underTest.leftEP.isSender shouldBe true
			}
			"3. And configure the Right Endpoint as Receiver" in {
				underTest.rightEP.owner.asInstanceOf[Actor].self shouldBe rightDestination
				underTest.rightEP.peerOwner.! shouldBe leftDestination
				underTest.rightEP.isInstanceOf[ReversibleChannel.Endpoint[Material]] shouldBe true
				underTest.rightEP.isSender shouldBe false
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
				expectMsg("Received Acknowledgement at right")
				harnessProbe.expectMsg("Completed Switch true")
				expectMsg(secondLoadMsg)
				harnessProbe.expectMsg(secondLoadMsg)
				harnessProbe.expectMsg(s"Reverse Load acknowledged $otherLoad")
			}
		}
	}
}
