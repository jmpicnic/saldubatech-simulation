/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor._
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.ddes.testHarness.ProcessorSink
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

	"A Shuttle" when {
		val globalClock = testKit.spawn(Clock())

		val testController = testKit.createTestProbe[ControllerMessage]


		val shuttleObserver = testKit.createTestProbe[(Clock.Tick, Shuttle.ShuttleNotification)]

		val shuttleHarnessSink = new ProcessorSink[Shuttle.ShuttleNotification](shuttleObserver.ref, globalClock)

		val shuttleHarness = testKit.spawn(shuttleHarnessSink.init)

		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		val testActor = testKit.createTestProbe[ProcessorMessage]

		val physics = new Shuttle.ShuttleTravel(2, 6, 4, 8, 8)
		val shuttleProcessor = Shuttle.ShuttleProcessor("undertest", physics, globalClock, testController.ref)
		val underTest = testKit.spawn(shuttleProcessor.init, "undertest")

		val loadProbe = new MaterialLoad("loadProbe")

		case class SequenceWrapper(tick: Clock.Tick) extends ActionCommand(testActor.ref, tick)


		"A. Register Itself for configuration" should {
			//			globalClock ! RegisterMonitor(testController.ref)
			//			testController.expectMessage(RegisteredClockMonitors(1))

			//			testController.expectMessage(StartedOn(0L))
			globalClock ! StartTime(0L)
			val firstAction = SequenceWrapper(0L)
			globalClock ! StartActionOnReceive(firstAction)


			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor(underTest))
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
				shuttleObserver.expectMessage((10L, Shuttle.Loaded(loadCommand)))
				shuttleObserver.expectNoMessage(500 millis)
			}
			"A04 Reject a command to load again" in {
				val loadCommand = Shuttle.Load(Shuttle.OnRight(0), loadProbe)
				println(s"Sender is: ${shuttleHarness}")
				underTest ! ProcessCommand(shuttleHarness, 4L, loadCommand)
				shuttleObserver.expectMessage(500 millis, (4L, Shuttle.UnacceptableCommand(loadCommand,s"Command not applicable while at place")))
				shuttleObserver.expectNoMessage(500 millis)
 			}
			"A05 Go To a given position in the travel time" in {
				val moveCommand = Shuttle.GoTo(Shuttle.OnRight(10))
				underTest ! ProcessCommand(shuttleHarness, 2L, moveCommand)
				cmdrProbe.expectMessage(500 millis, Shuttle.Arrived(moveCommand))
				cmdrProbe.expectNoMessage(500 millis)
			}
			"A06 Reject an unload request for the wrong location"  in {
				val probeRef = new Shuttle.Ref[MaterialLoad]()
				val unloadCommand = Shuttle.Unload(Shuttle.OnRight(17), probeRef)
				underTest ! ProcessCommand(shuttleHarness, 2L, unloadCommand)
				cmdrProbe.expectMessage(Shuttle.UnacceptableCommand(unloadCommand,s"Current Location ${Shuttle.OnRight(10)} incompatible with ${Shuttle.OnRight(17)}"))
				cmdrProbe.expectNoMessage(500 millis)
			}
			"A07 Unload the tray with the original content" in {
				val probeRef = new Shuttle.Ref[MaterialLoad]()
				val unloadCommand = Shuttle.Unload(Shuttle.OnRight(10), probeRef)
				underTest ! ProcessCommand(shuttleHarness, 2L, unloadCommand)
				cmdrProbe.expectMessage(500 millis, Shuttle.Unloaded(unloadCommand))
				cmdrProbe.expectNoMessage(500 millis)
			}
			"A08 Reject a command to unload again" in {
				val probeRef = new Shuttle.Ref[MaterialLoad]()
				val unloadCommand = Shuttle.Unload(Shuttle.OnRight(10), probeRef)
				underTest ! ProcessCommand(shuttleHarness, 2L, unloadCommand)
				cmdrProbe.expectMessage(Shuttle.UnacceptableCommand(unloadCommand,s"Command not applicable while at place"))
				cmdrProbe.expectNoMessage(500 millis)
			}
		}
	}
}
