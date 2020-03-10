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

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	"A Shuttle" when {
		val globalClock = testKit.spawn(Clock())

		val testObserver = testKit.createTestProbe[String]("TestObserver")

		val testController = testKit.createTestProbe[ControllerMessage]


		val harnessObserver = testKit.createTestProbe[(Clock.Tick, Shuttle.ShuttleNotification)]

		val shuttleHarnessSink = new ProcessorSink[Shuttle.ShuttleNotification](harnessObserver.ref, globalClock)

		val shuttleHarness = testKit.spawn(shuttleHarnessSink.init)

		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		val testActor = testKit.createTestProbe[ProcessorMessage]

		val physics = new Shuttle.ShuttleTravel(2, 6, 4, 8, 8)
		val shuttleProcessor = Shuttle.buildProcessor("undertest", physics, globalClock, testController.ref)
		val underTest = testKit.spawn(shuttleProcessor.init, "undertest")

		val loadProbe = new MaterialLoad("loadProbe")
		val locAt0 = Shuttle.ShuttleLocation(Shuttle.OnRight(0))
		locAt0.store(loadProbe)
		val locAt7 = Shuttle.ShuttleLocation(Shuttle.OnRight(7))
		val locAt10 = Shuttle.ShuttleLocation(Shuttle.OnRight(10))


		"A. Register Itself for configuration" should {
			//			globalClock ! RegisterMonitor(testController.ref)
			//			testController.expectMessage(RegisteredClockMonitors(1))

			//			testController.expectMessage(StartedOn(0L))


			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor(underTest))
				testController.expectNoMessage(500 millis)
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				underTest ! ConfigurationCommand(shuttleHarness, 0L, Shuttle.Configure(locAt0))
				globalClock ! StartTime(0L)
				testController.expectMessage(CompleteConfiguration(underTest))
				harnessObserver.expectMessage((0L, Shuttle.CompleteConfiguration(underTest)))
				testController.expectNoMessage(500 millis)
				harnessObserver.expectNoMessage(500 millis)
			}
			"A03 Load the tray when empty with the acquire delay" in {
				val loadCommand = Shuttle.Load(locAt0)
				underTest ! ProcessCommand(shuttleHarness, 2L, loadCommand)
				harnessObserver.expectMessage(500 millis, (10L, Shuttle.Loaded(loadCommand)))
				harnessObserver.expectNoMessage(500 millis)
				locAt0.isEmpty should be (true)
			}
			"A04 Reject a command to load again" in {
				val loadCommand = Shuttle.Load(locAt0)
				log.info(s"Sender is: ${shuttleHarness}")
				underTest ! ProcessCommand(shuttleHarness, 11L, loadCommand)
				harnessObserver.expectMessage(500 millis,
					(11L, Shuttle.UnacceptableCommand(loadCommand,s"Command not applicable when Tray loaded with ${Some(loadProbe)} at $locAt0")))
				harnessObserver.expectNoMessage(500 millis)
 			}
			"A05 Go To a given position in the travel time" in {
				val moveCommand = Shuttle.GoTo(locAt10)
				underTest ! ProcessCommand(shuttleHarness, 2L, moveCommand)
				harnessObserver.expectMessage(500 millis, (12L, Shuttle.Arrived(moveCommand)))
				harnessObserver.expectNoMessage(500 millis)
			}
			"A06 Reject an unload request for the wrong location"  in {
				val unloadCommand = Shuttle.Unload(locAt7)
				underTest ! ProcessCommand(shuttleHarness, 14L, unloadCommand)
				harnessObserver.expectMessage(500 millis, (14L, Shuttle.UnacceptableCommand(unloadCommand,s"Current Location $locAt10 incompatible with $locAt7")))
				harnessObserver.expectNoMessage(500 millis)
				locAt7.inspect should be (None)
			}
			"A07 Unload the tray with the original content" in {
				val unloadCommand = Shuttle.Unload(locAt10)
				underTest ! ProcessCommand(shuttleHarness, 15L, unloadCommand)
				harnessObserver.expectMessage(500 millis,(23L, Shuttle.Unloaded(unloadCommand,loadProbe)))
				harnessObserver.expectNoMessage(500 millis)
				locAt10.inspect should be (Some(loadProbe))
			}
			"A08 Reject a command to unload again" in {
				val unloadCommand = Shuttle.Unload(locAt10)
				underTest ! ProcessCommand(shuttleHarness, 24L, unloadCommand)
				harnessObserver.expectMessage(500 millis, (24L, Shuttle.UnacceptableCommand(unloadCommand,s"Command not applicable while at place")))
				harnessObserver.expectNoMessage(500 millis)
			}
		}
	}
}
