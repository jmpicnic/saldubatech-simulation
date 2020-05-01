/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.xswitch

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.CarriageTravel
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object XSwitchSpec {


}

class XSwitchSpec 
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	import XSwitchFixtures._
	import XSwitchHelpers._

	override val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController: SimulationController.Ref = simControllerProbe.ref

	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, XSwitch.Notification)]
	val xcManagerRef = xcManagerProbe.ref
	val xcManagerProcessor = new ProcessorSink(xcManagerRef, clock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "XCManager")


	"A Lift Level" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		// Channels
		val chIb1: Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, XSwitch.XSwitchSignal] = new InboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2: InboundChannelImpl[XSwitch.XSwitchSignal] = new InboundChannelImpl[XSwitch.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val obInduct = Map(0 -> new Channel.Ops(chIb1), 1 -> new Channel.Ops(chIb2))

		val obDischarge = Map((-1, new Channel.Ops(new OutboundChannelImpl[XSwitch.XSwitchSignal](() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge"))))

		val config = XSwitch.Configuration(physics, Map.empty, Map.empty, obInduct, obDischarge, 0)


		// Sources & sinks
		val sources = config.outboundInduction.values.map(ibOps => new SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sourceActors: Seq[Processor.Ref] = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2)).toSeq

		val dischargeSink =  new SinkFixture(config.outboundDischarge.head._2, false)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", clock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		implicit val iCtx = clock
		val underTestProcessor = XSwitch.buildProcessor("underTest",config)
		val underTest = testKit.spawn(underTestProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest): _*)
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
				enqueueConfigure(underTest, xcManager, 0L, XSwitch.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> XSwitch.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => enqueueConfigure(act, xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				enqueueConfigure(dischargeActor, xcManager, 0L, DownstreamConfigure)
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
				enqueue(sourceActors.head, sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(15L -> XSwitch.LoadArrival(chIb1.name, probeLoad))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = XSwitch.Transfer(chIb1.name, "Discharge")
				enqueue(underTest, xcManager, 155, transferCmd)
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				xcManagerProbe.expectMessage(174L -> XSwitch.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge")
			}
		}
		"C. Transfer a load from one collector to the discharge" when {
			val transferCmd = XSwitch.Transfer(chIb1.name, "Discharge")
			"C01. it receives the command first" in {
				enqueue(underTest, xcManager, 190L, transferCmd)
			}
			"C02. and then receives the load in the origin channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				enqueue(sourceActors.head, sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				xcManagerProbe.expectMessage(272L -> XSwitch.CompletedCommand(transferCmd))
			}
		}
	}
}
