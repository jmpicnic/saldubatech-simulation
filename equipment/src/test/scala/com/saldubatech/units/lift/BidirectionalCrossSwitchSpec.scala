/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.units.lift
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object BidirectionalCrossSwitchSpec {


}

class BidirectionalCrossSwitchSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import BidirectionalXSFixtures._
	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	implicit val globalClock = testKit.spawn(Clock())
	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref

	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, BidirectionalCrossSwitch.Notification)]
	val xcManagerRef = xcManagerProbe.ref
	val xcManagerProcessor = new ProcessorSink(xcManagerRef, globalClock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "XCManager")


	"A Lift Level" should {

		val physics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)
		val carriageProcessor = Carriage.buildProcessor("carriage", physics, globalClock, simController)

		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound2")
		val obInduct = Seq(0 -> new Channel.Ops(chIb1), 1 -> new Channel.Ops(chIb2))

		val obDischarge = Seq((-1, new Channel.Ops(new OutboundChannelImpl(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge"))))

		val config = BidirectionalCrossSwitch.Configuration(carriageProcessor, Seq.empty, Seq.empty, obInduct, obDischarge, 0)


		// Sources & sinks
		val sources = config.outboundInduction.map(_._2).map(ibOps => new SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new SinkFixture(config.outboundDischarge.head._2)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", globalClock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		val underTestProcessor = BidirectionalCrossSwitch.buildProcessor("underTest", config)
		val underTest = testKit.spawn(underTestProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest): _*)
				globalClock ! Clock.StartTime()
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
				underTest ! Processor.ConfigurationCommand(xcManager, 0L, BidirectionalCrossSwitch.NoConfigure)
				simControllerProbe.expectMessageType[Processor.RegisterProcessor] // SHuttle ref is not available here.
				simControllerProbe.expectMessageType[Processor.CompleteConfiguration]
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> BidirectionalCrossSwitch.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				dischargeActor ! Processor.ConfigurationCommand(xcManager, 0L, DownstreamConfigure)
				testMonitorProbe.expectMessage(s"Received Configuration: ${DownstreamConfigure}")
				val actorsToConfigure: mutable.Set[Processor.Ref] = mutable.Set(sourceActors ++ Seq(dischargeActor): _*)
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
		"B. Transfer a load from one collector to the discharge" when {
			"B01. it receives the load in one channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(12L -> BidirectionalCrossSwitch.LoadArrival(chIb1.name, probeLoad))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = BidirectionalCrossSwitch.Transfer(chIb1.name, "Discharge")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 155, transferCmd))
				xcManagerProbe.expectMessage(174L -> BidirectionalCrossSwitch.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
			}
		}
		"C. Transfer a load from one collector to the discharge" when {
			val transferCmd = BidirectionalCrossSwitch.Transfer(chIb1.name, "Discharge")
			"C01. it receives the command first" in {
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 190L, transferCmd))
			}
			"C02. and then receives the load in the origin channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				xcManagerProbe.expectMessage(269L -> BidirectionalCrossSwitch.CompletedCommand(transferCmd))
			}
		}
	}
}
