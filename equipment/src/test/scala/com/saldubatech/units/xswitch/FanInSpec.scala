/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.xswitch

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor.Ref
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.CarriageTravel
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object FanInSpec {
}

class FanInSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import XSwitchFixtures._
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

	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, XSwitch.Notification)]
	val xcManagerRef = xcManagerProbe.ref
	val xcManagerProcessor = new ProcessorSink(xcManagerRef, globalClock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "FanInManager")


	"A FanIn" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val obInduct = Map(0 -> new Channel.Ops(chIb1), 1 -> new Channel.Ops(chIb2))

		val obDischarge = Map((-1, new Channel.Ops(new OutboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge"))))

		val config = XSwitch.Configuration(physics, Map.empty, Map.empty, obInduct, obDischarge, 0)


		// Sources & sinks
		val sources = config.outboundInduction.values.map(ibOps => new SourceFixture(ibOps)(testMonitor, this)).toSeq
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new SinkFixture(config.outboundDischarge.head._2, false)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", globalClock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		val underTestProcessor = XSwitch.buildProcessor("underTest", config)
		val underTest = testKit.spawn(underTestProcessor.init, "underTest")

		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest): _*)
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
				underTest ! Processor.ConfigurationCommand(xcManager, 0L, XSwitch.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> XSwitch.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				dischargeActor ! Processor.ConfigurationCommand(xcManager, 0L, DownstreamConfigure)
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
		"B. Transfer a load from one collector to the discharge" when {
			"B01. it receives the load in one channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(15L -> XSwitch.LoadArrival(chIb1.name, probeLoad))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = XSwitch.Transfer(chIb1.name, "Discharge")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 155, transferCmd))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				xcManagerProbe.expectMessage(174L -> XSwitch.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
			}
		}
		"C. Transfer a load from one induct to the discharge" when {
			val transferCmd = XSwitch.Transfer(chIb1.name, "Discharge")
			"C01. it receives the command first" in {
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 190L, transferCmd))
			}
			"C02. and then receives the load in the origin channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 240L, probeLoadMessage)
				var found = 0
				testMonitorProbe.fishForMessage(500 millis) {
					case "Load MaterialLoad(First Load) released on channel Discharge" =>
						found += 1
						if(found == 4) FishingOutcome.Complete
						else FishingOutcome.Continue
					case "FromSender: First Load"=>
						found += 1
						if(found == 4) FishingOutcome.Complete
						else FishingOutcome.Continue
					case "Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)" =>
						found += 1
						if(found == 4) FishingOutcome.Complete
						else FishingOutcome.Continue
					case "Load MaterialLoad(First Load) arrived to Sink via channel Discharge" =>
						found += 1
						if(found == 4) FishingOutcome.Complete
						else FishingOutcome.Continue
				}
				xcManagerProbe.expectMessage(272L -> XSwitch.CompletedCommand(transferCmd))
			}
		}
	}
}
