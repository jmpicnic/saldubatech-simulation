/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.gg1

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.{AgentTemplate, Clock}
import com.saldubatech.ddes.AgentTemplate._
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, PSimSignal, SimRef, SimSignal}
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.test.BaseSpec._
import com.saldubatech.test.ClockEnabled
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

import scala.concurrent.duration._


class JobFlowSpec
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

	"A G/G/1 Station" when {
		val simulationControllerProbe = testKit.createTestProbe[ControllerMessage]
		val simulationController = simulationControllerProbe.ref
		val testObserverProbe = testKit.createTestProbe[(Tick, DomainSignal)]
		val testObserver = testObserverProbe.ref


		val managerProcessor = new ProcessorSink(testObserverProbe.ref, clock)
		val manager = testKit.spawn(managerProcessor.init, "manager")

		val interArrivalTime = () => 12L
		val nJobs = 10
		val processingTime = () => 10L

		val generatorTemplate = new Generator("generator", interArrivalTime, nJobs)
		val generatorAgent = AgentTemplate.buildAgent(generatorTemplate)(clock, testKit, simulationController)

		val processorTemplate = new JobProcessor("processor", processingTime)
		val processorAgent = AgentTemplate.buildAgent(processorTemplate)(clock, testKit, simulationController)

		val receiveJob: (Job, Tick) => Unit = (jb, tick) => {testObserverProbe.ref ! tick -> jb}
		val sinkTemplate = new Sink("sink", receiveJob)
		val sinkAgent = AgentTemplate.buildAgent(sinkTemplate)(clock, testKit, simulationController)

		"A. Initialized" should {
			"A01. Send a registration message to the controller" in {
				simulationControllerProbe.expectMessage(RegisterProcessor[Job](generatorAgent))
				simulationControllerProbe.expectMessage(RegisterProcessor[Job](processorAgent))
				simulationControllerProbe.expectMessage(RegisterProcessor[Job](sinkAgent))
				clock ! Clock.RegisterMonitor(simulationController.ref)
				simulationControllerProbe.expectMessage(Clock.RegisteredClockMonitors(1))
				simulationControllerProbe.expectNoMessage(500 millis)
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				startTime()
				simulationControllerProbe.expectMessage(Clock.StartedOn(0L))
				enqueueConfigure(sinkAgent, manager, 0L, Sink.Configure)
				testObserverProbe.expectMessage(0L -> Sink.CompleteConfiguration(sinkAgent))
				enqueueConfigure(processorAgent, manager, 0L, JobProcessor.Configure(sinkAgent))
				testObserverProbe.expectMessage(0L -> JobProcessor.CompleteConfiguration(processorAgent))
				enqueueConfigure(generatorAgent, manager, 0L, Generator.Configure(processorAgent))
				testObserverProbe.expectMessage(500 millis, 0L -> Generator.CompleteConfiguration(generatorAgent))
				simulationControllerProbe.expectMessages(
					Clock.NoMoreWork(0L),
					Clock.NoMoreWork(0L),
					Clock.NoMoreWork(0L),
					Clock.NoMoreWork(0L),
					RegistrationConfigurationComplete(sinkAgent),
					RegistrationConfigurationComplete(generatorAgent),
					RegistrationConfigurationComplete(processorAgent)
				)
				testObserverProbe.expectNoMessage(500 millis)
				simulationControllerProbe.expectNoMessage(500 millis)
			}
		}
		"B Trigger jobs from the generator" when {
			"B01 The Generator receives a Go command" in {
				val goTime = 5
				enqueue(generatorAgent, manager, goTime, Generator.Go)
				def expected(idx: Int): Long = goTime + 12*(idx + 1) + 10
				val expectedCompletions = (0 to nJobs).map(idx => expected(idx) -> Job.Completion(expected(idx), s"Job_$idx"))
				println(expectedCompletions.mkString("\n"))
				testObserverProbe.expectMessages(expectedCompletions: _*)
				testObserverProbe.expectNoMessage(500 millis)
			}
		}
	}
}
