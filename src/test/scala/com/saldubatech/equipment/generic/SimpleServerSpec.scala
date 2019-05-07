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
import com.saldubatech.base.{OneWayChannel, Material}
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


class SimpleServerSpec extends BaseActorSpec(ActorSystem("StepProcessorTest"),
	Some(LogEventSpooler(Logger("com.salduba.events.eventCollector")))) {


	val deliveryPolicy: StepProcessor.DeliveryPolicy = new StepProcessor.DeliveryPolicy() {
					override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] =
						mutable.Queue() ++= finishedGoods
	}

	val outboundSelector: Discharge.SelectionPolicy  = new Discharge.SelectionPolicy {
		override def dischargeSelection(load: Material,
		                                outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String =
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

	def runUntil(tick: Long): (Option[Material], Long) => Boolean = (_,at) => at > tick

	var source: ActorRef = gw.simActorOf(
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
				runUntil(25),
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


	val sink: ActorRef = gw.simActorOf(Props(new Sink("sink", gw){
		override def newJobArrival(operation: Material, at: Long): Unit = {
			super.newJobArrival(operation, at)
			testActor ! (operation.uid, at)
		}
	}), "sink")

	val arrivalChannel = new OneWayChannel[Material](5, "arrivalChannel")

	val departureChannel = new OneWayChannel[Material](5, "departureChannel")


	"A SimpleServer" when {
		"initializing" should {
			"install its executor and connections" in {
				gw.configure(source, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(arrivalChannel)))
				gw.configure(underTest, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(arrivalChannel)), ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(departureChannel)))
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
				while(
					expectMsgPF[Boolean](500 millis)({
						case msg:String if msg.matches(s"Generate Load.*") => specLog.info(msg); true
						case msg:Tuple2[String, Long] if msg._1.matches("Material.*") => specLog.info(msg.toString());tick = msg._2;false
						case msg: Any => specLog.info(s"Unknown msg: $msg");false
					})) loadN += 1
				for (i <- 2 until loadN)
					expectMsgPF[Boolean](500 millis)({
					case msg:Tuple2[String, Long] if msg._1.matches(s"Material$i") => tick = msg._2;true})
				//expectMsg((s"Material$i", )
//				gw.epoch.now shouldBe tick // This is consistent with 7 jobs --> @TODO Is this the right number?
			}
		}
	}
}
