/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.xswitch

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.CarriageTravel
import com.saldubatech.units.lift.LoadAwareXSwitch
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object LoadAwareFanInLargeDischargeBufferSpec {


}

class LoadAwareFanInLargeDischargeBufferSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
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

	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref


	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, LoadAwareXSwitch.Notification)]
	val xcManagerRef = xcManagerProbe.ref
	val xcManagerProcessor = new ProcessorSink(xcManagerRef, clock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "FanInManager")


	"A Lift Level" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		// Channels
		val chIb1 = new InboundChannelImpl[LoadAwareXSwitch.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl[LoadAwareXSwitch.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val obInduct = Map(0 -> new Channel.Ops(chIb1), 1 -> new Channel.Ops(chIb2))

		val obDischarge = Map((-1, new Channel.Ops(new OutboundChannelImpl[LoadAwareXSwitch.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge"))))

		val config = LoadAwareXSwitch.Configuration(physics, 5, Map.empty, Map.empty, obInduct, obDischarge, 0)


		// Sources & sinks
		val sources = config.outboundInduction.values.map(ibOps => new SourceFixture(ibOps)(testMonitor, this)).toSeq
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new SinkFixture(config.outboundDischarge.head._2, true)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", clock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		implicit val clk = clock
		val underTestProcessor = LoadAwareXSwitch.buildProcessor("underTest", config)
		val underTest = testKit.spawn(underTestProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest): _*)
				startTime()
				simControllerProbe.fishForMessage(3 second) {
					case Processor.RegisterProcessor(pr) =>
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
				enqueueConfigure(underTest, xcManager, 0L, LoadAwareXSwitch.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> LoadAwareXSwitch.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => enqueueConfigure(act, xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				enqueueConfigure(dischargeActor, xcManager, 0L, DownstreamConfigure)
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				val actorsToConfigure: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ Seq(dischargeActor): _*)
				log.info(s"Actors to Configure: $actorsToConfigure")
				simControllerProbe.fishForMessage(500 millis) {
					case Processor.CompleteConfiguration(pr) =>
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
				xcManagerProbe.expectMessage(15L -> LoadAwareXSwitch.LoadArrival(chIb1.name, firstLoad))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCommand = LoadAwareXSwitch.Transfer(firstLoad, "Discharge")
				enqueue(underTest, xcManager, 155, transferCommand)
				xcManagerProbe.expectMessage(174L -> LoadAwareXSwitch.CompletedCommand(transferCommand))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
			}
		}
		"C. Transfer a second load from one collector to the discharge" when {
			val secondLoad = MaterialLoad("Second Load")
			val secondTransferCommand = LoadAwareXSwitch.Transfer(secondLoad, "Discharge")
			val thirdLoad = MaterialLoad("Third Load")
			val thirdTransferCommand = LoadAwareXSwitch.Transfer(thirdLoad, "Discharge")
			"C01. it receives the command first" in {
				enqueue(underTest, xcManager, 190L, secondTransferCommand)
			}
			"C02. and then receives the load in the origin channel, waiting for a free discharge card" in {
				val probeLoadMessage = TestProbeMessage("Second Load", secondLoad)
				enqueue(sourceActors.head, sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Second Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Second Load)")
				xcManagerProbe.expectMessage(275L -> LoadAwareXSwitch.CompletedCommand(secondTransferCommand))
			}
			"C03. One more load to force the shuttle to error out and the Lift to waitforslot" in {
				val probeLoadMessage = TestProbeMessage("Third Load", thirdLoad)
				enqueue(sourceActors.head, sourceActors.head, 288L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Third Load")
				xcManagerProbe.expectMessage(301L -> LoadAwareXSwitch.LoadArrival("Inbound1", thirdLoad))
				enqueue(underTest, xcManager, 301L, thirdTransferCommand)
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Third Load)")
				xcManagerProbe.expectNoMessage(500 millis)
			}
			"C04. and then the discharge consumes load, second load is sent and third command is complete" in {
				enqueue(dischargeActor, dischargeActor, 323L, ConsumeLoad)
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(First Load),Ob1_c2))")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge")
				testMonitorProbe.expectMessage("Load MaterialLoad(Second Load) arrived to Sink via channel Discharge")
				enqueue(dischargeActor, dischargeActor, 340L, ConsumeLoad)
				xcManagerProbe.expectMessage(331L -> LoadAwareXSwitch.CompletedCommand(thirdTransferCommand)) //330?
        xcManagerProbe.expectNoMessage(1000 millis)
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
