/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.xswitch

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.AgentTemplate.{RegisterProcessor, RegistrationConfigurationComplete}
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, SimRef, SimSignal}
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{AgentTemplate, Clock}
import com.saldubatech.protocols.{Equipment, EquipmentManagement, MaterialLoad}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.Channel
import com.saldubatech.units.carriage.CarriageTravel
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object FanInLargeDischargeBufferSpec {


}

class FanInLargeDischargeBufferSpec
	extends AnyWordSpec
		with Matchers
		with AnyWordSpecLike
		with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	import XSwitchFixtures._
	import XSwitchHelpers._

	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	val simControllerProbe = testKit.createTestProbe[ControllerMessage]
	implicit val simController = simControllerProbe.ref


	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, EquipmentManagement.XSwitchNotification)]
	val xcManagerRef = xcManagerProbe.ref
	val xcManagerProcessor = new ProcessorSink(xcManagerRef, clock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "FanInManager")


	"A Lift Level" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		// Channels
		val chIb1 = new InboundChannelImpl[Equipment.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl[Equipment.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val obInduct = Map(0 -> new Channel.Ops(chIb1), 1 -> new Channel.Ops(chIb2))

		val obDischarge = Map((-1, new Channel.Ops(new OutboundChannelImpl[Equipment.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge"))))

		val config = XSwitch.Configuration(physics, Map.empty, Map.empty, obInduct, obDischarge, 0)


		// Sources & sinks
		val sources = config.outboundInduction.values.map(ibOps => new SourceFixture(ibOps)(testMonitor, this)).toSeq
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new AgentTemplate.Wrapper(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new SinkFixture(config.outboundDischarge.head._2, true)(testMonitor, this)
		val dischargeProcessor:  AgentTemplate.Wrapper[Equipment.MockSinkSignal] = new AgentTemplate.Wrapper("discharge", clock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		implicit val clk = clock
		val underTestProcessor = XSwitch.buildProcessor("underTest", config)
		val underTest = testKit.spawn(underTestProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[SimRef[_ <: DomainSignal]] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest): _*)
				startTime()
				simControllerProbe.fishForMessage(3 second) {
					case RegisterProcessor(pr) =>
						if (actorsToRegister.contains(pr)) {
							actorsToRegister -= pr
							if (actorsToRegister isEmpty) FishingOutcome.Complete
							else FishingOutcome.Continue
						} else {
							FishingOutcome.Fail(s"Unexpected processor registration received $pr")
						}
				}
				actorsToRegister.isEmpty should be(true)
			}
			"A02. Register its Lift when it gets Configured" in {
				enqueueConfigure(underTest, xcManager, 0L, XSwitch.NoConfigure)
				simControllerProbe.expectMessage(RegistrationConfigurationComplete[Equipment.XSwitchSignal](underTest))
				xcManagerProbe.expectMessage(0L -> XSwitch.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => enqueueConfigure(act, xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				enqueueConfigure(dischargeActor, xcManager, 0L, DownstreamConfigure)
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				val actorsToConfigure: mutable.Set[SimRef[_ <: DomainSignal]] = mutable.Set(sourceActors ++ Seq(dischargeActor): _*)
				log.info(s"Actors to Configure: $actorsToConfigure")
				simControllerProbe.fishForMessage(500 millis) {
					case RegistrationConfigurationComplete(pr) =>
						log.info(s"Seeing $pr")
						if (actorsToConfigure.contains(pr)) {
							actorsToConfigure -= pr
							if (actorsToConfigure isEmpty) FishingOutcome.Complete
							else FishingOutcome.Continue
						} else {
							FishingOutcome.Fail(s"Unexpected processor registration received $pr")
						}
				}
				actorsToConfigure.isEmpty should be(true)
			}
		}
		val firstLoad = MaterialLoad("First Load")
		"B. Transfer a load from one collector to the discharge" when {
			"B01. it receives the load in one channel" in {
				val probeLoadMessage = TestProbeMessage("First Load", firstLoad)
				enqueue(sourceActors.head, sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(15L -> XSwitch.LoadArrival(chIb1.name, firstLoad))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCommand = XSwitch.Transfer(chIb1.name, "Discharge")
				enqueue(underTest, xcManager, 155, transferCommand)
				xcManagerProbe.expectMessage(174L -> XSwitch.CompletedCommand(transferCommand))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
			}
		}
		"C. Transfer a second load from one collector to the discharge" when {
			val secondTransferCommand = XSwitch.Transfer(chIb1.name, "Discharge")
			val thirdTransferCommand = XSwitch.Transfer(chIb1.name, "Discharge")
			"C01. it receives the command first" in {
				enqueue(underTest, xcManager, 190L, secondTransferCommand)
			}
			"C02. and then receives the load in the origin channel, waiting for a free discharge card" in {
				val probeLoad = MaterialLoad("Second Load")
				val probeLoadMessage = TestProbeMessage("Second Load", probeLoad)
				enqueue(sourceActors.head, sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Second Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Second Load)")
				xcManagerProbe.expectMessage(272L -> XSwitch.CompletedCommand(secondTransferCommand))
			}
			"C03. One more load to force the shuttle to error out and the Lift to waitforslot" in {
				val thirdLoad = MaterialLoad("Third Load")
				val probeLoadMessage = TestProbeMessage("Third Load", thirdLoad)
				enqueue(sourceActors.head, sourceActors.head, 285L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Third Load")
				xcManagerProbe.expectMessage(298L -> XSwitch.LoadArrival("Inbound1", thirdLoad))
				enqueue(underTest, xcManager, 298L, thirdTransferCommand)
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Third Load)")
				xcManagerProbe.expectNoMessage(500 millis)
			}
			"C04. and then the discharge consumes load, second load is sent and third command is complete" in {
				enqueue(dischargeActor, dischargeActor, 320L, ConsumeLoad)
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(First Load),Ob1_c2))")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge")
				testMonitorProbe.expectMessage("Load MaterialLoad(Second Load) arrived to Sink via channel Discharge")
				enqueue(dischargeActor, dischargeActor, 342L, ConsumeLoad)
				xcManagerProbe.expectMessage(328L -> XSwitch.CompletedCommand(thirdTransferCommand)) //330?
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(Second Load),Ob1_c1))")
				testMonitorProbe.expectMessage("Load MaterialLoad(Second Load) released on channel Discharge")
				testMonitorProbe.expectMessage("Load MaterialLoad(Third Load) arrived to Sink via channel Discharge")
				enqueue(dischargeActor, dischargeActor, 360L, ConsumeLoad)
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(Third Load),Ob1_c2))")
				testMonitorProbe.expectMessage("Load MaterialLoad(Third Load) released on channel Discharge")
			}
		}
	}
}
