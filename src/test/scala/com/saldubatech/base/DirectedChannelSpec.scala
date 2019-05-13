/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.base
import com.saldubatech.base.DirectedChannel.{AcknowledgeLoad, ConfigureEnds, ConfigureStarts, TransferLoad}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.equipment.elements.{Discharge, Induct, StepProcessor}
import com.saldubatech.test.utils.BaseActorSpec

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class DirectedChannelSpec extends BaseActorSpec(ActorSystem("MaterialChannelUnidirectionalSpec")) {


	val underTest: DirectedChannel[Material] = new DirectedChannel[Material](3, "underTest Channel") {

		override def registerStart(owner: DirectedChannel.Source[Material]): DirectedChannel.Start[Material] = {
			testActor ! "Registering Left"
			super.registerStart(owner)
		}

		override def registerEnd(owner: DirectedChannel.Sink[Material]): DirectedChannel.End[Material] = {
			testActor ! "Registering Right"
			super.registerEnd(owner)
		}
	}

	abstract class DummyIntake(name: String) extends SimActorImpl(name, gw)
		with StepProcessor
		with DirectedChannel.Destination[Material] {
		override val p_capacity: Int = 3
		override val p_executor: ActorRef = null
		override val p_jobSelectionPolicy: StepProcessor.JobSelectionPolicy = new StepProcessor.JobSelectionPolicy {
			override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] =
				queue.toList
		}
		override val p_deliveryPolicy: StepProcessor.DeliveryPolicy = new StepProcessor.DeliveryPolicy {
			override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] =
						mutable.Queue() ++= finishedGoods
		}

		override def newJobArrival(material: Material, at: Long): Unit =
			testActor ! s"New Job Arrival $material"


		override def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit =
			testActor ! s"Notified of outbound available at $at"


		override def process(from: ActorRef, at: Long): Processing = processing(from, at)

	} // class DummyIntake

	class MockSource extends SimActorImpl("origin", gw)
		with DirectedChannel.Destination[Material] {
		override def configure: Configuring = channelStartConfiguring

		override def process(from: ActorRef, at: Long): Processing = {
			case AcknowledgeLoad(channel, load, resource) =>
				testActor ! s"Notified of outbound available at $at"
				testActor ! s"Restored Outbound Capacity at $at"
				underTest.start.doRestoreResource(from, at, resource)
		}
		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}

		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {}

	}

	val origin: ActorRef = gw.simActorOf(Props(new MockSource()), "origin")


	var lastJob: Material = _

	class MockDestination extends SimActorImpl("sink", gw) with DirectedChannel.Destination[Material] {
		override def configure: Configuring = channelEndConfiguring

		override def process(from: ActorRef, at: Long): Processing = {
			case cmd: TransferLoad[Material] =>
				underTest.end.doLoadReceiving(from, at, cmd.load, cmd.resource)
				lastJob = cmd.load
				testActor ! s"New Job Arrival ${cmd.load}"
		}

		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}

		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {}
	}

	val destination: ActorRef = gw.simActorOf(Props(new MockDestination()),"destination")



	"A Material Channel" when {
		"created" must {
			"allow registering Origin and Destination" in {
				gw.configure(origin, ConfigureStarts[DirectedChannel[Material]](Seq(underTest)))
				gw.configure(destination, ConfigureEnds[DirectedChannel[Material]](Seq(underTest)))
				expectMsgAllOf("Registering Left", "Registering Right")
				assert(underTest.end != null)
				assert(underTest.start != null)
			}
			"call the destination consumeInput when sent a load" in {
				Await.result(gw.isConfigurationComplete, 1 second) shouldBe Gateway.SimulationState.READY
				gw.activate()
				val material = Material("Material"+1)
				underTest.start.sendLoad(material, 0) shouldBe true
				expectMsg(s"New Job Arrival $material")
			}
			"reject the 4th call to sendLoad" in {
				val material2 = Material("Material"+2)
				val material3 = Material("Material"+3)
				val material4 = Material("Material"+4)
				underTest.start.sendLoad(material2, 0) shouldBe true
				underTest.start.sendLoad(material3, 0) shouldBe true
				underTest.start.sendLoad(material4, 0) shouldBe false
				expectMsg(s"New Job Arrival $material2")
				expectMsg(s"New Job Arrival $material3")
				expectNoMessage(500 millis)
			}
			"Free up a resource when doneWithLoad" in {
				underTest.end.doneWithLoad(lastJob, 0)
				expectMsgAllOf(
					"Restored Outbound Capacity at 0",
					"Notified of outbound available at 0")
			}
			"Then accept one more load" in {
				val material = Material("Material"+4)
				underTest.start.sendLoad(material, 0) shouldBe true
				expectMsg(s"New Job Arrival $material")
			}
		}
	}
}
