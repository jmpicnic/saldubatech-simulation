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

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.base.channels.v1.{AbstractChannel, OneWayChannel}
import com.saldubatech.base.Material
import com.saldubatech.ddes.Epoch.Action
import com.saldubatech.ddes.GlobalClock.ActNow
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.{Gateway, SimActorImpl}
import com.saldubatech.events.LogEventSpooler
import com.saldubatech.test.utils.BaseActorSpec
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class InductSpec extends BaseActorSpec(ActorSystem("InductTest"),
	Some(LogEventSpooler(Logger("com.salduba.events.eventCollector")))) {

	abstract class DummyIntake(name: String, val p_outboundSelector: Discharge.SelectionPolicy) extends SimActorImpl(name, gw)
		with StepProcessor {
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

		override def newJobArrival(job: Material, at: Long): Unit = {
			testActor ! job
			testActor ! s"New Job Arrival $job"
		}

		override def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit = {
			testActor ! "Notified of outbound available"
		}

	}


	val underTest: ActorRef = gw.simActorOf(Props(
		new DummyIntake("underTest",null) with Induct {
			val name = uid
			override protected val induct: StepProcessor.Induct = null
			override protected def discharge: Discharge = null
			override def process(from: ActorRef, at: Long): Processing =
				inducting(from, at) orElse
					processing(from, at) orElse
					{case ("trigger consume", job:Material) => consumeInput(job, at)}
			override def configure: Configuring = inductConfiguring

			override def onRestore(via: OneWayChannel.Endpoint[Material], tick: Long): Unit = {}
		}
	), "underTest")

	val oSelector: Discharge.SelectionPolicy = new Discharge.SelectionPolicy {
		override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = {
			outQueues.head._1
		}
	}
	val origin: ActorRef = gw.simActorOf(Props(
		new DummyIntake("origin", oSelector)
			with Discharge
			with OneWayChannel.Destination[Material] {
			val name = uid
			override protected def induct: Induct = null

			override def process(from: ActorRef, at: Long): Processing =
				discharging(from,at) orElse {
					case ("send material", material: Material) =>
						log.debug("Invoking deliver material")
						deliver(material, at)
				}
			override def configure: Configuring = dischargeConfiguring

			override def outboundAvailable(via: AbstractChannel.Endpoint[Material, _], at: Long): Unit = {
				testActor ! s"Restoring materialProbe"
				super.outboundAvailable(via, at)
			}

			override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {}
		}
	), "origin")

	"An Induct" when {
		val channel = new OneWayChannel[Material](1, "channel")
		gw.configure(origin, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(channel)))
		gw.configure(underTest, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(channel)))
		"called from the processor to consume an input" must {
			gw.injectInitialAction(origin, ("send material", Material("materialProbe")))
			val configured = Await.result(gw.isConfigurationComplete, 1 second)
			gw.activate()
			"invoke the outbox of the incoming channel to release the load" in {
				specLogger.debug("Expecting configuration to be running...")
				Await.result(gw.isConfigurationComplete, 1 second) shouldBe Gateway.SimulationState.RUNNING
				val material = expectMsgType[Material]
				expectMsg("New Job Arrival materialProbe")
				val action = Action(testActor, underTest, ("trigger consume", material), 0)
				gw.getClock ! ActNow(action)
				underTest ! action
				expectMsg("Restoring materialProbe")
			}
		}
	}



}
