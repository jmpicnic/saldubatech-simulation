/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate._
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, SimSignal}
import com.saldubatech.test.BaseSpec._
import com.saldubatech.test.ClockEnabled
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

import scala.concurrent.duration._



class ProcessorSpec
	extends AnyWordSpec
		with Matchers
    with AnyWordSpecLike
    with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	val testKit = ActorTestKit()
	case class MockDomainMessage(msg: String)

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    testKit.shutdownTestKit()
  }

	"A Processor" when {
		//val globalClock = testKit.spawn(Clock())

		val testController = testKit.createTestProbe[ControllerMessage]
		val mockProcessorSender = testKit.createTestProbe[SimSignal]
		val mockProcessorReceiver = testKit.createTestProbe[SimSignal]
		val testActor = testKit.createTestProbe[String]

		case class DomainType(domainId: String) extends Identification.Impl(domainId) with DomainSignal

		def mockRunner: DomainRun[DomainType] = (ctx:  FullSignallingContext[DomainType,_ <: DomainSignal]) => {
			case DomainType(msg) =>
				testActor.ref ! msg
				ctx.signal(mockProcessorReceiver.ref, DomainType(s"Answering: $msg"))
				mockRunner
		}
		val mockConfigurer: DomainConfigure[DomainType] = new DomainConfigure[DomainType] {
			override def configure(config: DomainType)(implicit ctx:  FullSignallingContext[DomainType, _ <: DomainSignal]): DomainRun[DomainType] = config match {
				case DomainType(msg) =>
					testActor.ref ! msg
					mockRunner
			}
		}

		val testProcessor = new AgentTemplate.Wrapper("underTest", clock, testController.ref, mockConfigurer)
		val underTest = testKit.spawn(testProcessor.init, "underTest")

		val action1 = SourcedRun(mockProcessorSender.ref, 0, DomainType("MOCK PROCESS COMMAND"))
		val action2 = SourcedRun(underTest, 0L, DomainType("Answering: MOCK PROCESS COMMAND"))
		val action3 = SourcedRun(mockProcessorSender.ref, 23L, DomainType("MOCK PROCESS COMMAND2"))

		var receivedCmd: SourcedRun[_, DomainType] = null

		"A. Register Itself for configuration" should {
			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor[DomainType](underTest))
				clock ! Clock.RegisterMonitor(testController.ref)
				testController.expectMessage(Clock.RegisteredClockMonitors(1))
				testController.expectNoMessage(500 millis)
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				startTime()
				testController.expectMessage(Clock.StartedOn(0L))
				enqueueConfigure(underTest, mockProcessorSender.ref, 0L, DomainType("MockConfiguration"))
				testActor.expectMessage("MockConfiguration")
				testController.expectMessages(
					Clock.NoMoreWork(0L),
					Clock.NoMoreWork(0L),
					RegistrationConfigurationComplete[DomainType](underTest))
				testController.expectNoMessage(500 millis)
			}
			"A03 Process runtime messages after being configured" in {
				underTest ! action1
				receivedCmd = mockProcessorReceiver.expectMessage(action2)
				testActor.expectMessage("MOCK PROCESS COMMAND")
				testController.expectNoMessage(500 millis)
				mockProcessorReceiver.expectNoMessage(500 millis)
				testActor.expectNoMessage(500 millis)
			}
			"A04 Process runtime messages at a delayed time" in {
				underTest ! action3
				testController.expectNoMessage(200 millis)
				clock ! CompleteAction(receivedCmd)
				mockProcessorReceiver.expectMessage(SourcedRun(underTest, 23L, DomainType("Answering: MOCK PROCESS COMMAND2")))
				testActor.expectMessage("MOCK PROCESS COMMAND2")
				testController.expectMessage(Clock.NotifyAdvance(0, 23))
				testController.expectNoMessage(500 millis)
				mockProcessorReceiver.expectNoMessage(500 millis)
				testActor.expectNoMessage(500 millis)
			}
		}
	}
}
