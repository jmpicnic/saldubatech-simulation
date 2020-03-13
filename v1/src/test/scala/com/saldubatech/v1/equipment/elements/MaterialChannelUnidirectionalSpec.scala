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

package com.saldubatech.v1.equipment.elements

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.v1.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.v1.base.channels.v1.{AbstractChannel, OneWayChannel}
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl}
import com.saldubatech.test.utils.BaseActorSpec

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class MaterialChannelUnidirectionalSpec extends BaseActorSpec(ActorSystem("MaterialChannelUnidirectionalSpec")) {

	abstract class DummyIntake(val name: String) extends SimActorImpl(name, gw)
		with StepProcessor
		with OneWayChannel.Destination[Material] {
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

	val origin: ActorRef = gw.simActorOf(Props(new DummyIntake("origin")
		with Discharge {
		val p_outboundSelector: Discharge.SelectionPolicy = new Discharge.SelectionPolicy {
			override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = {
				outQueues.head._1
			}
		}
		override def process(from: ActorRef, at: Long): Processing = discharging(from, at)
		override def configure: Configuring = dischargeConfiguring

		override protected def induct: StepProcessor.Induct = null

		override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {}

		override def onRestore(via: OneWayChannel.Endpoint[Material], tick: Long): Unit = {
			testActor ! s"Restored Outbound Capacity at $tick"
			super.onRestore(via, tick)
		}
	}), "origin")  // ActorRef Origin

	var lastJob: Material = _

	val destination: ActorRef = gw.simActorOf(Props(new DummyIntake("destination")
		with Induct {
		override def process(from: ActorRef, at: Long): Processing = inducting(from, at)
		override def configure: Configuring = inductConfiguring


		override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {
			lastJob = load
			super.onAccept(via, load, tick)
		}

		override protected def induct: StepProcessor.Induct = null
		override def onRestore(via: OneWayChannel.Endpoint[Material], tick: Long): Unit = {
			fail("onRestore should never be called in the destination equipment")
		}
		override protected def discharge: Discharge = null
	}), "destination")


	"A Material Channel" when {
		"created" must {

			object underTest extends OneWayChannel[Material](3, "underTest Channel") {

				var left: OneWayChannel.Endpoint[Material] = _

				override def registerLeft(owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
					testActor ! "Registering Left"
					left = super.registerLeft(owner)
					left
				}

				var right: OneWayChannel.Endpoint[Material] = _
				override def registerRight (owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
					testActor ! "Registering Right"
					right = super.registerRight(owner)
					right
				}

			}

			"allow registering Origin and Destination" in {
				gw.configure(origin, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(underTest)))
				gw.configure(destination, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(underTest)))
				expectMsgAllOf("Registering Left", "Registering Right")
				assert(underTest.right != null)
				assert(underTest.left != null)
			}
			"call the destination consumeInput when sent a load" in {
				Await.result(gw.isConfigurationComplete, 1 second) shouldBe Gateway.SimulationState.READY
				gw.activate()
				val material = Material("Material"+1)
				underTest.left.sendLoad(material, 0) shouldBe true
				expectMsg(s"New Job Arrival $material")
			}
			"reject the 4th call to sendLoad" in {
				val material2 = Material("Material"+2)
				val material3 = Material("Material"+3)
				val material4 = Material("Material"+4)
				underTest.left.sendLoad(material2, 0) shouldBe true
				underTest.left.sendLoad(material3, 0) shouldBe true
				underTest.left.sendLoad(material4, 0) shouldBe false
				expectMsg(s"New Job Arrival $material2")
				expectMsg(s"New Job Arrival $material3")
				expectNoMessage(500 millis)
			}
			"Free up a resource when doneWithLoad" in {
				underTest.right.doneWithLoad(lastJob, 0)
				expectMsgAllOf(
					"Restored Outbound Capacity at 0",
					"Notified of outbound available at 0")
			}
			"Then accept one more load" in {
				val material = Material("Material"+4)
				underTest.left.sendLoad(material, 0) shouldBe true
				expectMsg(s"New Job Arrival $material")
			}
		}
	}
}
