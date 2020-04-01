/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.ddes


import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, TestKit, TestProbe}
import com.saldubatech.v1.ddes.Epoch.{Action, ActionRequest}
import com.saldubatech.v1.ddes.GlobalClock._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


class GlobalClockSpec(_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with WordSpecLike
    with BeforeAndAfterAll {


  def this() = this(ActorSystem("SimActorSpec"))

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    //shutdown(system)
  }

	"A GlobalClock" when {
		val underTest: ActorRef = system.actorOf(GlobalClock.props(None), "underTest")
		val secondProbe = TestProbe()
		"is not yet operating" should {
			"allow registration of Time Monitors" in {
				underTest ! RegisterTimeMonitor(testActor)
				underTest ! RegisterTimeMonitor(secondProbe.testActor)
				expectMsg(Registered(testActor, 1))
				secondProbe.expectMsg(Registered(secondProbe.testActor, 2))
			}
			"and allow de-registration" in {
				underTest ! DeregisterTimeMonitor(secondProbe.testActor)
				secondProbe.expectMsg(Deregistered(1))
			}
			"enqueue all Actions that are sent to it" in {
				EventFilter.debug(message = "GlobalClock now: 0 : Enqueuing An initial message at 0 while stopped", occurrences = 1) intercept {
					underTest ! Enqueue(ActionRequest(testActor, secondProbe.testActor, "An initial message", 0))
				}
				EventFilter.debug(message = "GlobalClock now: 0 : Enqueuing A second initial message at 10 while stopped", occurrences = 1) intercept {
					underTest ! Enqueue(ActionRequest(testActor, secondProbe.testActor, "A second initial message", 10))
				}
			}
			"once it is given the Start Time messages" should {
				"advance its internal clock" in {
					//EventFilter.debug(message="Advancing Clock from: 0 to: 0", occurrences = 1) intercept {
					EventFilter.debug(message = "Ready to Advance at 0 with future epochs: 2", occurrences = 1) intercept {
						underTest ! StartTime()
						expectMsg(NotifyAdvance(0, 0))
					}
					//}
				}
				"Process the messages it was given before for time 0 and try to advance again" in {
					secondProbe.expectMsg(Action(testActor, secondProbe.testActor, "An initial message", 0))
					EventFilter.debug(message = "GlobalClock now: 0 : Registering StartReceive An initial message", occurrences = 1) intercept {
						underTest ! StartActionOnReceive(Action(testActor, secondProbe.testActor, "An initial message", 0))
					}
					EventFilter.debug(message = "GlobalClock now: 0 : Registering Complete An initial message", occurrences = 1) intercept {
							underTest ! CompleteAction(Action(testActor, secondProbe.testActor, "An initial message", 0))
					}
				}
				"then advance the clock and send the additional delayed messages" in {
					expectMsg(NotifyAdvance(0, 10))
					secondProbe.expectMsg(Action(testActor, secondProbe.testActor, "A second initial message", 10))
				}
			}
		}
	}
}
