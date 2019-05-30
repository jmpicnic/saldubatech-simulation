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
import com.saldubatech.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.base.channels.v1.{AbstractChannel, OneWayChannel}
import com.saldubatech.base.Material
import com.saldubatech.equipment.elements.{Discharge, SimpleRandomExecution, StepProcessor}
import com.saldubatech.equipment.elements.SimpleRandomExecution.ConfigureOwner
import com.saldubatech.equipment.generic.Source.Activate
import com.saldubatech.events.{EventCollector, LogEventSpooler}
import com.saldubatech.test.utils.BaseActorSpec
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.languageFeature.postfixOps


class SourceSpec extends BaseActorSpec(ActorSystem("SourceTest")) {



	val deliveryPolicy: StepProcessor.DeliveryPolicy = new StepProcessor.DeliveryPolicy() {
		override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] = {
			if (finishedGoods isEmpty) specLog.debug("Finished goods is empty")
			mutable.Queue() ++= finishedGoods
		}
	}

	object outboundSelector extends Discharge.SelectionPolicy {
		override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = outQueues.keys.head
	}

	"A Source" when {
		"created" must {

			var underTest: ActorRef = null

			val constantDelayer: () => Long = () => 33

			val executor: ActorRef = gw.simActorOf(Props(
				new SimpleRandomExecution("Executor", gw, constantDelayer) {
					override def registerOwner(_owner: ActorRef): Unit = {
						assert(_owner == underTest)
						testActor ! s"Registering owner: ${_owner.path}"
						super.registerOwner(_owner)
					}
				}),
				"Executor"
			)
			object endCondition {
				var count: Int = 0

				def countTo(n: Int): (Option[Material], Long) => Boolean = (_, _) => {
					count += 1; count > n
				}
			}


			val log = Logger("com.saldubatech.events.eventCollector")
			val eventSpooler = LogEventSpooler(log)
			val eventCollector = system.actorOf(EventCollector.props(eventSpooler))



			underTest = gw.simActorOf(
				Props(
					new Source("underTest",
						gw = gw,
						3,
						p_executor = executor,
						loadGenerator = Long => {
							testActor ! s"Generate Load ${endCondition.count}"
							Some(Material(s"Material${endCondition.count}"))
						},
						endCondition = endCondition.countTo(3),
						deliveryPolicy,
						outboundSelector
					)
				),
				"underTest"
			)




			var lastJob: Material = null
			val destination: ActorRef = gw.simActorOf(
				Props(
					new Sink("destination", gw) {
						override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, tick: Long): Unit = {
							lastJob = load
							super.onAccept(via, load, tick)//job, at)
							testActor ! s"Received ${load.uid} at the Sink"
						}
					}
				),
				"destination"
			)

			object channel$OneWay extends OneWayChannel[Material](100, "underTest Channel") {
				var in: OneWayChannel.Endpoint[Material] = _
				var out: OneWayChannel.Endpoint[Material] = _

				override def registerRight(owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
					testActor ! "Registering Right"
					out = super.registerRight(owner)
					out
				}

				override def registerLeft(owner: AbstractChannel.Destination[Material, OneWayChannel.Endpoint[Material]]): OneWayChannel.Endpoint[Material] = {
					testActor ! s"Registering Left"
					in = super.registerLeft(owner)
					in
				}

			}

			"Register itself as the owner of the executor and register the channel" in {
				gw.configure(executor, ConfigureOwner(underTest))
				gw.configure(underTest, ConfigureLeftEndpoints(Seq(channel$OneWay)))
				gw.configure(destination, ConfigureRightEndpoints(Seq(channel$OneWay)))
				expectMsgAllOf("Registering Left", "Registering Right", s"Registering owner: ${underTest.path}")
				testActor ! "Finished Configuration"
			}
				"complete registering Origin and Destination in the channel" in {
					assert(channel$OneWay.out != null)
					assert(channel$OneWay.in != null)
				}
			"start generating loads when sent the Activate message" in {
				expectMsg("Finished Configuration")
				gw.injectInitialAction(underTest, Activate())
				gw.activate()

				//				gw.tellTo(testActor, underTest, Deactivate())

				expectMsg("Generate Load 0")
				expectMsg("Generate Load 1")
				expectMsg("Generate Load 2")
				expectMsg("Generate Load 3") // this is fake generation, but tolerated.
				expectMsg("Received Material0 at the Sink")
				expectMsg("Received Material1 at the Sink")
				expectMsg("Received Material2 at the Sink")
			}
		}
	}
}
