/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.ddes

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock.{ClockShuttingDown, CompleteAction, DeregisterMonitor, Enqueue, NoMoreWork, NotifyAdvance, RegisterMonitor, RegisteredClockMonitors, StartTime, StartedOn, Tick}
import com.saldubatech.ddes.Processor.{ProcessCommand, ProcessorMessage}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}


class ClockSpec
	extends WordSpec
		with Matchers
    with WordSpecLike
    with BeforeAndAfterAll {
	val testKit = ActorTestKit()
	case class MockDomainMessage(msg: String)

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    testKit.shutdownTestKit()
  }

	"A GlobalClock" when {
		val underTest = testKit.spawn(Clock())

		val testController = testKit.createTestProbe[ControllerMessage]
		val testController2 = testKit.createTestProbe[ControllerMessage]
		val mockProcessorSender = testKit.createTestProbe[ProcessorMessage]
		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		"A. is not yet operating" should {
			"A01. allow registration of Time Monitors" in {
				underTest ! RegisterMonitor(testController.ref)
				testController.expectMessage(RegisteredClockMonitors(1))
			}
			"A02. and allow de-registration" in {
				underTest ! RegisterMonitor(testController2.ref)
				testController.expectMessage(RegisteredClockMonitors(2))
				testController2.expectMessage(RegisteredClockMonitors(2))
				underTest ! DeregisterMonitor(testController.ref)
				testController2.expectMessage(RegisteredClockMonitors(1))
				testController.expectNoMessage
				underTest ! DeregisterMonitor(testController2.ref)
				testController2.expectNoMessage
			}
			"A03. Start" in {
				underTest ! RegisterMonitor(testController.ref)
				testController.expectMessage(RegisteredClockMonitors(1))
				val probeMsg = ProcessCommand[MockDomainMessage](mockProcessorSender.ref, 40L, MockDomainMessage("MOCK_MOCK"))
				underTest ! Enqueue(mockProcessorReceiver.ref, probeMsg)
				underTest ! StartTime(37L)
				testController.expectMessage(StartedOn(37L))
				testController.expectMessage(NotifyAdvance(37L, 40L))
				mockProcessorReceiver.expectMessage(probeMsg)
				underTest ! Enqueue(mockProcessorReceiver.ref, probeMsg)
				mockProcessorReceiver.expectMessage(probeMsg)
				val probeMsg2 = ProcessCommand[MockDomainMessage](mockProcessorSender.ref, 43L, MockDomainMessage("MOCK_MOCK2"))
				underTest ! Enqueue(mockProcessorReceiver.ref, probeMsg2)
				mockProcessorReceiver.expectNoMessage
				underTest ! CompleteAction(probeMsg)
				mockProcessorReceiver.expectMessage(probeMsg2)
				testController.expectMessage(NotifyAdvance(40, 43))
				underTest ! CompleteAction(probeMsg2)
				testController.expectMessage(NoMoreWork(43L))
				underTest ! Clock.StopTime
				testController.expectMessage(ClockShuttingDown(43L))
				testController.expectNoMessage
				testKit.stop(underTest)
			}
			/* "once it is given the Start Time messages" should {
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
		*/
		}
	}
}
