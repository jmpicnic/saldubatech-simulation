/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.ActorSystem
import akka.event.Logging.StandardOutLogger
import akka.pattern._
import akka.testkit.{EventFilter, TestProbe}
import akka.util.Timeout
import com.saldubatech.util.Lang._
import com.saldubatech.base.Material
import com.saldubatech.ddes.Epoch.Action
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.elements.SimpleRandomExecution.{CompleteProcessing, CompleteStaging, ConfigureOwner, Process}
import com.saldubatech.test.utils.BaseActorSpec

import scala.concurrent.Await
import scala.concurrent.duration._


class SimpleRandomExecutionSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import com.saldubatech.ddes.GlobalClock.WhatTimeIsIt

	class TestSimProbe(probeName: String, gw: Gateway) extends TestProbe(system, probeName) {
		override def expectMsg[T](obj: T): T = {
			val act = obj.asInstanceOf[Action]
			super.expectMsg(obj)
		}

	}

	def this() = this(ActorSystem("SimpleRandiomExecutionSpec"))

	val logger = new StandardOutLogger()

	"A SimpleRandomExecution" when {
		val constantDelayer: () => Long = () => 33

		val underTest = gw.simActorOf(SimpleRandomExecution.props("UnderTestExecution", gw, constantDelayer),
			"UnderTestExecution")

		val material = Material("material")
		"newly created" must {
			"accept a configuration message with its owner" in {
				EventFilter.debug(message = "Received configuration confirmation from UnderTestExecution, " +
					"pending: 0, advised: 0", occurrences = 1) intercept {
					gw.configure(underTest, ConfigureOwner(testActor))
				}
			}
		}
		"sent a Process Message" must {
			"do nothing before starting" in {
				gw.injectInitialAction(underTest, Process("process command", material.?))
				expectNoMessage(500 millis)
			}
			"respond with an inmmediate CompleteStaging Message" in {

				gw.activate()

				//expectMsg(Action(underTest, testActor, CompleteStaging("processResource"), 0))
				//CompleteStaging(commandId, material)
				expectMsgPF(3.seconds, "Expect & Complete the 'CompleteStaging' message"){
          case act: Action if act.from == underTest && act.to == testActor &&
	          act.targetTick == 0 && act.msg.isInstanceOf[CompleteStaging] =>
	          gw.receiveAction(act)
						gw.completeAction(act) // Argh! potential race condition but the expect below should be O.K. with 3 secs.
        }

				expectMsg(Action(underTest, testActor, CompleteProcessing("process command", material.?, material.?), 33))

				Await.result((gw.getClock ? WhatTimeIsIt())(Timeout(500 millis)).mapTo[Long], 1 second) shouldBe 33

			}
		}
	}

}
