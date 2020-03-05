/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor.{CompleteConfiguration, ConfigurationCommand, ProcessCommand, ProcessorControlCommand, ProcessorMessage, RegisterProcessor}
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.concurrent.duration._



class ProcessorSpec
	extends WordSpec
		with Matchers
    with WordSpecLike
    with BeforeAndAfterAll
		with LogEnabled {
	val testKit = ActorTestKit()
	case class MockDomainMessage(msg: String)

  override def beforeAll: Unit = {

  }

  override def afterAll: Unit = {
    testKit.shutdownTestKit()
  }

	"A Processor" when {
		val globalClock = testKit.spawn(Clock())

		val testController = testKit.createTestProbe[ControllerMessage]
		val mockProcessorSender = testKit.createTestProbe[ProcessorMessage]
		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		val testActor = testKit.createTestProbe[String]

		case class DomainType(id: String)

		val action1UUID = java.util.UUID.randomUUID.toString
		val action2UUID = java.util.UUID.randomUUID.toString

		def mockRunner: Processor.DomainRun[DomainType] = (ctx: Processor.CommandContext[DomainType]) => {
			case DomainType(msg) =>
				testActor.ref ! msg
				ctx.tellTo(mockProcessorReceiver.ref, DomainType(s"Answering: $msg"))
				mockRunner
		}
		/*val mockRunner = new Processor.DomainRun[DomainType]{
			override def process(processMessage: DomainType)(implicit ctx: Processor.CommandContext[DomainType]): Processor.DomainRun[DomainType] = processMessage match {
				case DomainType(msg) =>
					testActor.ref ! msg
					ctx.tellTo(mockProcessorReceiver.ref, DomainType(s"Answering: $msg"))
					this
			}
		}*/
		val mockConfigurer: Processor.DomainConfigure[DomainType] = new Processor.DomainConfigure[DomainType] {
			override def configure(config: DomainType)(implicit ctx: Processor.CommandContext[DomainType]): Processor.DomainRun[DomainType] = config match {
				case DomainType(msg) =>
					testActor.ref ! msg
					mockRunner
			}
		}

		val testProcessor = new Processor("underTest", globalClock, testController.ref, mockConfigurer)
		val underTest = testKit.spawn(testProcessor.init, "Clock")

		val action1 = ProcessCommand(mockProcessorSender.ref, 0, DomainType("MOCK PROCESS COMMAND"))
		val action2 = ProcessCommand(underTest, 0L, DomainType("Answering: MOCK PROCESS COMMAND"))
		val action3 = ProcessCommand(mockProcessorSender.ref, 23L, DomainType("MOCK PROCESS COMMAND2"))

		var receivedCmd: ProcessCommand[DomainType] = null

		"A. Register Itself for configuration" should {
			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor(underTest))
				testController.expectNoMessage(500 millis)
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				underTest ! ConfigurationCommand(mockProcessorSender.ref, 0L, DomainType("MockConfiguration"))
				testActor.expectMessage("MockConfiguration")
				testController.expectMessage(CompleteConfiguration(underTest))
				testController.expectNoMessage(500 millis)
			}
			"A03 Process runtime messages after being configured" in {
				globalClock ! StartTime(0L)
				underTest ! action1
				receivedCmd = mockProcessorReceiver.expectMessage(action2)
				testActor.expectMessage("MOCK PROCESS COMMAND")
				testController.expectNoMessage(500 millis)
				mockProcessorReceiver.expectNoMessage(500 millis)
				testActor.expectNoMessage(500 millis)
			}
			"A04 Process runtime messages at a delayed time" in {
				underTest ! action3
				log.debug("Sent two commands, now in A04")
				globalClock ! CompleteAction(receivedCmd)
				mockProcessorReceiver.expectMessage(ProcessCommand(underTest, 23L, DomainType("Answering: MOCK PROCESS COMMAND2")))
				testActor.expectMessage("MOCK PROCESS COMMAND2")
				testController.expectNoMessage(500 millis)
				mockProcessorReceiver.expectNoMessage(500 millis)
				testActor.expectNoMessage(500 millis)
			}
		}
	}
}
