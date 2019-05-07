/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.generic

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.base.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.base.{AbstractChannel, OneWayChannel, Material}
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.equipment.elements.Discharge
import com.saldubatech.events.LogEventSpooler
import com.saldubatech.resource.DiscreteResourceBox
import com.saldubatech.test.utils.BaseActorSpec
import com.typesafe.scalalogging.Logger

import scala.languageFeature.postfixOps


class SinkSpec extends BaseActorSpec(ActorSystem("StepProcessorTest"),
	Some(LogEventSpooler(Logger("com.salduba.events.eventCollector")))) {

	abstract class OriginIntake(name: String) extends SimActor(name, gw) with Discharge.Processor {
		override def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit = {
			testActor ! s"Notified of outbound available"
		}
	}

	val origin: ActorRef = gw.simActorOf(Props(new OriginIntake("origin") with Discharge with OneWayChannel.Destination[Material]{
		override val p_outboundSelector: Discharge.SelectionPolicy = new Discharge.SelectionPolicy {
			override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = {
				outQueues.head._1
			}
		}

		override def configure: Configuring = dischargeConfiguring
		override def dischargeProcessor: Discharge.Processor = this
		override def process(from: ActorRef, at: Long): Processing = discharging(from, at)

		override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {}
	}), "origin")

var lastJob: Material = _
val destination: ActorRef = gw.simActorOf(Props(new Sink("destination", gw){
	override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {
		lastJob = load
		super.onAccept(via, load, tick)
	}
}), "destination")

object underTest extends OneWayChannel[Material](3, "underTest Channel") {
	var in: OneWayChannel.Endpoint[Material] = _
	var out: OneWayChannel.Endpoint[Material] = _

	override def registerRight(owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
		testActor ! "Registering Right"
		out = super.registerRight(owner)
		out
	}

	override def registerLeft(owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
		testActor ! "Registering Left"
		in = super.registerLeft(owner)
		in
	}

}

	"A Sink" when {
		"created" must {
			"allow registering Origin and Destination" in {
				gw.configure(origin, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(underTest)))
				gw.configure(destination, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(underTest)))

				expectMsgAllOf("Registering Right", "Registering Left")
				assert(underTest.out != null)
				assert(underTest.in != null)
			}
			"call the destination consumeInput when sent a load and process it right away" in {
				val material = Material("Material"+1)
				underTest.in.sendLoad(material, 0) shouldBe true
//				expectMsg(s"Done with $material")
				expectMsg("Notified of outbound available")
			}
			"be done with new loads in quick sequence" in {
				val material2 = Material("Material"+2)
				val material3 = Material("Material"+3)
				val material4 = Material("Material"+4)
				underTest.in.sendLoad(material2, 0) shouldBe true
				underTest.in.sendLoad(material3, 0) shouldBe true
				underTest.in.sendLoad(material4, 0) shouldBe true
//				expectMsg(s"Done with $material2")
				expectMsg("Notified of outbound available")
//				expectMsg(s"Done with $material3")
				expectMsg("Notified of outbound available")
//				expectMsg(s"Done with $material4")
				expectMsg("Notified of outbound available")
			}
			"Then accept one more load" in {
				val material = Material("Material"+5)
				underTest.in.sendLoad(material, 0) shouldBe true
//				expectMsg(s"Done with $material")
				expectMsg("Notified of outbound available")
				//eventCollector.get ! Report("destination", Event(0,OperationalEvent.Depart,"MockStation", material.uid))
			}
		}
	}
}