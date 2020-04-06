/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.unitsorter

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.UnitsFixture._
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object UnitSorter2Spec {


	class InboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal2](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal2
		type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal2
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal2

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with UnitSorterSignal2
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, UnitSorterSignal2, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal2
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal2
	}


}

class UnitSorter2Spec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import UnitSorter2Spec._

	val testKit = ActorTestKit()

	override def beforeAll: Unit = {}

	override def afterAll: Unit = testKit.shutdownTestKit()

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	implicit val globalClock = testKit.spawn(Clock())
	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref

	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, UnitSorter2.Notification)]
	val xcManagerProcessor = new ProcessorSink(xcManagerProbe.ref, globalClock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "XCManager")


	"A Unit Sorter" should {

		val physics = new CircularPathTravel(60, 25, 100)

		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound2")
		val inducts = Map(0 -> new Channel.Ops(chIb1), 30 -> new Channel.Ops(chIb2))

		val chDis1 = new OutboundChannelImpl(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge_1")
		val chDis2 = new OutboundChannelImpl(() => Some(10L), Set("Ob2_c1", "Ob2_c2"), 1, "Discharge_2")
		val discharges = Map(15 -> new Channel.Ops(chDis1), 45 -> new Channel.Ops(chDis2))

		val config = UnitSorter2.Configuration("underTest", 40, inducts, discharges, physics)


		// Sources & sinks
		val sources = config.inducts.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal2] => new SourceFixture(chOps)(testMonitor, this)}
		val sourceProcessors = sources.zip(Seq("induct_1", "induct_2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceRefs = sourceProcessors.map(t => testKit.spawn(t.init, t.processorName))

		val destinations: Seq[SinkFixture[UnitSorterSignal2]] =  config.discharges.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, UnitSorterSignal2, ChannelConnections.DummySinkMessageType] => new SinkFixture(chOps)(testMonitor, this)}
		val destinationProcessors = destinations.zipWithIndex.map{case (dstSink, idx) => new Processor(s"discharge_$idx", globalClock, simController, configurer(dstSink)(testMonitor))}
		val destinationRefs = destinationProcessors.map(proc => testKit.spawn(proc.init, proc.processorName))

		val underTestProcessor = UnitSorter2.buildProcessor(config)(globalClock, simController)
		val underTest = testKit.spawn(underTestProcessor.init, underTestProcessor.processorName)


		"A. Configure itself" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(sourceRefs ++ destinationRefs ++ Seq(underTest): _*)
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
			"A02. Process its configuration" in {
				underTest ! Processor.ConfigurationCommand(xcManager, 0L, UnitSorter2.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> UnitSorter2.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceRefs.foreach(act => act ! Processor.ConfigurationCommand(xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				destinationRefs.foreach(ref => ref ! Processor.ConfigurationCommand(xcManager, 0L, DownstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				val actorsToConfigure: mutable.Set[Processor.Ref] = mutable.Set(sourceRefs ++ destinationRefs: _*)
				simControllerProbe.fishForMessage(500 millis) {
					case Processor.CompleteConfiguration(pr) =>
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
			val probeLoad = MaterialLoad("First Load")
			"B01. it receives the load in one induct" in {
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceRefs.head ! Processor.ProcessCommand(sourceRefs.head, 55L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(65L -> UnitSorter2.LoadArrival(probeLoad, chIb1.name))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = UnitSorter2.Sort(probeLoad, chDis1.name)
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 130, transferCmd))
				testMonitorProbe.expectMessage("Received Load Acknowledgement through Channel: Inbound1 with MaterialLoad(First Load) at 130")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge_1 at 200")
				xcManagerProbe.expectMessage(190L -> UnitSorter2.CompletedCommand(transferCmd))
				destinationRefs.head ! Processor.ProcessCommand(destinationRefs.head, 500, ConsumeLoad)
				testMonitorProbe.expectMessage("Got load Some((MaterialLoad(First Load),Ob1_c2))")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge_1")
			}
		}
	}
}
