/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.unitsorter

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.UnitsFixture._
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object UnitSorterSpec {


	class InboundChannelImpl(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with UnitSorterSignal

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal


		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with ChannelConnections.DummySinkMessageType

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with ChannelConnections.DummySinkMessageType
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
	}


}

class UnitSorterSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	import UnitSorterSpec._

	override val testKit = ActorTestKit()

	override def beforeAll: Unit = {}

	override def afterAll: Unit = testKit.shutdownTestKit()

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref

	val xcManagerProbe = testKit.createTestProbe[(Clock.Tick, UnitSorter.Notification)]
	val xcManagerProcessor = new ProcessorSink(xcManagerProbe.ref, clock)
	val xcManager = testKit.spawn(xcManagerProcessor.init, "XCManager")


	"A Unit Sorter" should {

		val physics = new CircularPathTravel(60, 25, 100)

		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val inducts = Map(0 -> new Channel.Ops(chIb1), 30 -> new Channel.Ops(chIb2))

		val chDis1 = new OutboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge_0")
		val chDis2 = new OutboundChannelImpl(() => Some(10L), () => Some(3L), Set("Ob2_c1", "Ob2_c2"), 1, "Discharge_1")
		val discharges = Map(15 -> new Channel.Ops(chDis1), 45 -> new Channel.Ops(chDis2))

		val config = UnitSorter.Configuration(200, inducts, discharges, physics)


		// Sources & sinks
		val sources = config.inducts.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal] => new SourceFixture(chOps)(testMonitor, this)}
		val sourceProcessors = sources.zip(Seq("induct_1", "induct_2")).map(t => new Processor(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sourceRefs = sourceProcessors.map(t => testKit.spawn(t.init, t.processorName))

		val destinations: Seq[SinkFixture[UnitSorterSignal]] =  config.discharges.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType] => new SinkFixture(chOps)(testMonitor, this)}
		val destinationProcessors = destinations.zipWithIndex.map{case (dstSink, idx) => new Processor(s"discharge_$idx", clock, simController, configurer(dstSink)(testMonitor))}
		val destinationRefs = destinationProcessors.map(proc => testKit.spawn(proc.init, proc.processorName))

		val underTestProcessor = UnitSorter.buildProcessor("underTest", config)(clock, simController)
		val underTest = testKit.spawn(underTestProcessor.init, underTestProcessor.processorName)


		"A. Configure itself" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(sourceRefs ++ destinationRefs ++ Seq(underTest): _*)
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
			"A02. Process its configuration" in {
				enqueueConfigure(underTest, xcManager, 0L, UnitSorter.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> UnitSorter.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceRefs.foreach(act => enqueueConfigure(act, xcManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				destinationRefs.foreach(ref => enqueueConfigure(ref, xcManager, 0L, DownstreamConfigure))
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
				enqueue(sourceRefs.head, xcManager, 55L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(68L -> UnitSorter.LoadArrival(probeLoad, chIb1.name))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = UnitSorter.Sort(probeLoad, chDis1.name)
				enqueue(underTest, xcManager, 130L, transferCmd)
				testMonitorProbe.expectMessage("Received Load Acknowledgement through Channel: Inbound1 with MaterialLoad(First Load) at 130")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge_0 at 203")
				xcManagerProbe.expectMessage(190L -> UnitSorter.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge_0")
				testMonitorProbe.expectNoMessage(500 millis)
			}
		}
		"C. Transfer many loads from inputs to outputs" when {
			val loads = (0 until 200).map(idx => idx -> MaterialLoad(s"Load_${idx}_${idx%2}_${(idx%4)/2}"))
			val commands = loads.map{
				case (idx, load) => UnitSorter.Sort(load, s"Discharge_${(idx%4)/2}")
			}
			"C01. Sending all commands first" in {
				commands.foreach(cmd => enqueue(underTest, xcManager, 500, cmd))
				xcManagerProbe.expectNoMessage(500 millis)
			}
			"C02. Then sending the loads" in {
				loads.foreach {
					case (idx, load) => enqueue(sourceRefs(idx%2), xcManager, 600+idx, TestProbeMessage(load.lid, load))
				}
				var fromSenderCount = 0
				var acknowledgedLoadCount = 0
				var releasedToSinkCount = 0
				var foundAtSinkCount = 0
				def isDone = fromSenderCount == 200 && acknowledgedLoadCount == 200 && foundAtSinkCount == 200 && releasedToSinkCount == 200
				testMonitorProbe.fishForMessage(6 second){
					case msg: String if loads.map{case (idx, load) => s"FromSender: ${load.lid}"}.contains(msg) =>
						fromSenderCount += 1
						if(isDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case msg: String if msg.startsWith("Received Load Acknowledgement through Channel: Inbound") =>
						acknowledgedLoadCount += 1
						if(isDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case msg: String if msg.startsWith("Load MaterialLoad(Load_") && msg.contains("released on channel Discharge_") =>
						releasedToSinkCount += 1
						if(isDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case msg: String if msg.startsWith("Load MaterialLoad(Load_") =>
						foundAtSinkCount += 1
						if(isDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Found $other")
				}
				var sorterLoadsReceived = 0
				var sorterCompletedCommands = 0
				def isSorterDone = sorterCompletedCommands == 200 && sorterLoadsReceived == 200
				xcManagerProbe.fishForMessage(6 seconds){
					case (tick, UnitSorter.LoadArrival(load, channel)) =>
						sorterLoadsReceived += 1
						if(isSorterDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case (tick, UnitSorter.CompletedCommand(cmd)) =>
						sorterCompletedCommands += 1
						if(isSorterDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Unexpected Received $other")
				}
				testMonitorProbe.expectNoMessage(500 millis)
				xcManagerProbe.expectNoMessage(500 millis)
			}
		}
	}
}
