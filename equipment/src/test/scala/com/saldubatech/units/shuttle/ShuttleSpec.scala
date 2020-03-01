/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor._
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.concurrent.duration._


class ShuttleSpec
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


		val cmdrProbe = testKit.createTestProbe[Shuttle.ShuttleNotification]

		val cmdrHarnessDummy = testKit.createTestProbe[ProcessorMessage]

		val shuttleHarnessRunner = new Processor.DomainRun[Shuttle.ShuttleNotification]{
			override def process(processMessage: Shuttle.ShuttleNotification)(implicit ctx: Processor.CommandContext[Shuttle.ShuttleNotification]): Processor.DomainRun[Shuttle.ShuttleNotification] = processMessage match {
				case cmd: Shuttle.ShuttleNotification =>
					cmdrProbe.ref ! cmd
					this
			}
		}
		val shuttleHarnessConfigurer: Processor.DomainConfigure[Shuttle.ShuttleNotification] = new Processor.DomainConfigure[Shuttle.ShuttleNotification] {
			override def configure(config: Shuttle.ShuttleNotification)(implicit ctx: Processor.CommandContext[Shuttle.ShuttleNotification]): Processor.DomainRun[Shuttle.ShuttleNotification] = config match {
				case cmd: Shuttle.ShuttleNotification =>
					cmdrProbe.ref ! cmd
					shuttleHarnessRunner
			}
		}
		val shuttleHarnessProcessor = new Processor("ShuttleCommander", globalClock, testController.ref, shuttleHarnessConfigurer)
		case object ShuttleHarnessConfigure extends Shuttle.ShuttleNotification
		val shuttleHarness = testKit.spawn(shuttleHarnessProcessor.init)

		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		val testActor = testKit.createTestProbe[String]

		val physics = new Shuttle.ShuttleTravel(2, 6, 4, 8, 8)
		val shuttleProcessor = Shuttle.ShuttleProcessor("undertest", physics, globalClock, testController.ref)
		val underTest = testKit.spawn(shuttleProcessor.init, "undertest")

		val loadProbe = new MaterialLoad("loadProbe")



		"A. Register Itself for configuration" should {
//			globalClock ! RegisterMonitor(testController.ref)
//			testController.expectMessage(RegisteredClockMonitors(1))
			globalClock ! StartTime(0L)
//			testController.expectMessage(StartedOn(0L))

			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor(shuttleHarness))
				testController.expectMessage(RegisterProcessor(underTest))
				testController.expectNoMessage(500 millis)
				shuttleHarness ! ConfigurationCommand(cmdrHarnessDummy.ref,0L,ShuttleHarnessConfigure)
				testController.expectMessage(CompleteConfiguration(shuttleHarness))
				testController.expectNoMessage(500 millis)
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				underTest ! ConfigurationCommand(shuttleHarness, 0L, Shuttle.NoConfigure)
				testController.expectMessage(CompleteConfiguration(underTest))
				testController.expectNoMessage(500 millis)
			}
			"A03 Load the tray when empty with the acquire delay" in {
				val loadCommand = Shuttle.Load(Shuttle.OnRight(0), loadProbe)
				underTest ! ProcessCommand(shuttleHarness, 2L, loadCommand)
				cmdrProbe.expectMessage(Shuttle.Loaded(loadCommand))
				cmdrProbe.expectNoMessage(500 millis)
				globalClock ! CompleteAction(ProcessCommand(underTest,10L,Shuttle.Loaded(loadCommand)))
			}
			"A04 Reject a command to load again" in {
/*				val loadCommand = Shuttle.Load(Shuttle.OnRight(0), loadProbe)
				println(s"Sender is: ${shuttleCommander.ref}")
				underTest ! ProcessCommand(shuttleCommander.ref, 2L, loadCommand)
				shuttleCommander.expectMessage(ProcessCommand(underTest, 10L, Shuttle.UnacceptableCommand(loadCommand,s"Command not applicable while at place")))
				shuttleCommander.expectNoMessage(500 millis)
 */
			}
			"A05 Go To a given position in the travel time" in {

			}
			"A06 Unload the tray with the original content" in {

			}
			"A07 Reject a command to unload again" in {

			}
		}
	}
}
