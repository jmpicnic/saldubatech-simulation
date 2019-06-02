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

package com.saldubatech.equipment.generic

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.base.Material
import com.saldubatech.base.channels.v1.OneWayChannel
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.elements.SimpleRandomExecution.ConfigureOwner
import com.saldubatech.equipment.elements.{Discharge, SimpleRandomExecution, StepProcessor}
import com.saldubatech.equipment.generic.Source.Activate
import com.saldubatech.events.LogEventSpooler
import com.saldubatech.test.utils.BaseActorSpec
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class SourceServerSinkTimingSpec extends BaseActorSpec(ActorSystem("StepProcessorTest"),
	Some(LogEventSpooler(Logger("com.saldubatech.events.eventCollector")))) {


	val deliveryPolicy: StepProcessor.DeliveryPolicy = new StepProcessor.DeliveryPolicy() {
		override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] =
			mutable.Queue() ++= finishedGoods
	}

	val outboundSelector: Discharge.SelectionPolicy  = new Discharge.SelectionPolicy {
		override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String =
			outQueues.keys.head
	}

	val jobSelector: StepProcessor.JobSelectionPolicy = new StepProcessor.JobSelectionPolicy {
		override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] = queue.toList
	}

	def constantDelayer(delay: Long): () => Long = () => delay

	val sourceExecutor: ActorRef = gw.simActorOf(SimpleRandomExecution.props("sourceExecutor",gw,
		constantDelayer(50)), "sourceExecutor-50")

	var count: Int = 0
	def countTo(n: Int): (Material, Long) => Boolean = (_,_) => count >= n

	def runUntil(limit: Long): (Option[Material], Long) => Boolean =
		(_,at) => {if(at > limit) {testActor ! "Finished Generating";true} else false}

	val source: ActorRef = gw.simActorOf(
		Props(
			new Source("underTest",
				gw,
				3,
				sourceExecutor,
				Long => {
					count += 1
					testActor ! s"Generate Load $count"
					Some(Material(s"Material$count"))
				},
				runUntil(130),
				deliveryPolicy,
				outboundSelector
			)
		),
		"source"
	)

	val executor: ActorRef = gw.simActorOf(SimpleRandomExecution.props("serverExecutor", gw,
		constantDelayer(40)), "serverExecutor-40")

	val underTest: ActorRef = gw.simActorOf(Props(
		new SimpleServer("underTest", gw, 3, executor, jobSelector,deliveryPolicy,outboundSelector))
		, "underTest")

	val endOfLine: TestProbe = TestProbe()


	val sink: ActorRef = gw.simActorOf(Props(new Sink("sink", gw){
		override def newJobArrival(operation: Material, at: Long): Unit = {
			super.newJobArrival(operation, at)
			endOfLine.testActor ! (operation.uid, at)
		}
	}), "sink")

	val arrivalChannel = new OneWayChannel[Material](5, "arrivalChannel")

	val departureChannel = new OneWayChannel[Material](5, "departureChannel")


	"A SimpleServer" when {
		"initializing" should {
			"install its executor and connections" in {
				gw.configure(source, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(arrivalChannel)))
				gw.configure(underTest, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(arrivalChannel)),
					ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(departureChannel)))
				gw.configure(sink, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(departureChannel)))
				gw.configure(executor, ConfigureOwner(underTest))
				gw.configure(sourceExecutor, ConfigureOwner(source))
			}
		}
		"upon activation" should {
			"Run a few loads through the system" in {
				gw.injectInitialAction(source, Activate())
				Await.result(gw.isConfigurationComplete, 1 second) shouldBe Gateway.SimulationState.READY
				gw activate

				var loadN = 0
				var tick: Long = 0
				val expectedTick = Seq(90,90,90,140,140,140,190,190,190,240,240,240)
				while(expectMsgPF[Boolean](500 millis)({
					case msg:String if msg.matches(s"Generate Load.*") => true
					case msg:String if msg.matches("Finished Generating") => 	false
				}
				))
					loadN += 1
				for (i <- 1 until loadN) {
					endOfLine.expectMsg(500 millis, (s"Material$i", expectedTick(i - 1)))
				}
			}
		}
	}
}
