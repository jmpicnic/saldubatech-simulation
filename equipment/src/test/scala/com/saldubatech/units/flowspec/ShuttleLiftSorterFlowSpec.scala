/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.flowspec

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.BaseSpec.TestProbeExt
import com.saldubatech.transport
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.Carriage
import com.saldubatech.units.lift.BidirectionalCrossSwitch
import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.UnitsFixture.{DownstreamConfigure, SinkFixture, SourceFixture, TestProbeMessage, UpstreamConfigure, configurer}
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.unitsorter.{CircularPathTravel, UnitSorter, UnitSorterSignal}
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object ShuttleLiftSorterFlowSpec {
	import com.saldubatech.base.Identification

	object ShuttleBuilder {
		def build[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeDestinatationSignal >: ChannelConnections.ChannelDestinationMessage]
		(config: Shuttle.Configuration[InductSourceSignal, DischargeDestinatationSignal],
		 initialState: Shuttle.InitialState = Shuttle.InitialState(0, Map.empty))(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref = {
			val shuttleLevelProcessor = Shuttle.buildProcessor(config, initialState)
			actorCreator.spawn(shuttleLevelProcessor.init, config.name)
		}

		def configure(shuttle: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(shuttle, Shuttle.NoConfigure)
	}

	object LiftBuilder {
		import com.saldubatech.units.lift.BidirectionalCrossSwitch

		def build[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
			OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
		(config: BidirectionalCrossSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
		(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator) =
			actorCreator.spawn(BidirectionalCrossSwitch.buildProcessor(config).init, config.name)

		def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, BidirectionalCrossSwitch.NoConfigure)
	}

	object UnitSorterBuilder {
		import com.saldubatech.units.unitsorter.UnitSorter
		def build(config: UnitSorter.Configuration)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref =
			actorCreator.spawn(UnitSorter.buildProcessor(config).init, config.name)

		def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, UnitSorter.NoConfigure)
	}

	class LiftShuttleChannel(override val delay: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name:String)
		extends Channel[MaterialLoad, BidirectionalCrossSwitch.CrossSwitchSignal, Shuttle.ShuttleSignal](delay, cards, configuredOpenSlots, name) {
		override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Shuttle.ShuttleSignal
		override type PullSignal = Channel.PulledLoad[MaterialLoad] with Shuttle.ShuttleSignal
		override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
			new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
			new Channel.PulledLoadImpl(ld, idx, this.name) with Shuttle.ShuttleSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
			new transport.Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal
	}

	class SorterLiftChannel(override val delay: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name:String)
		extends Channel[MaterialLoad, UnitSorterSignal, BidirectionalCrossSwitch.CrossSwitchSignal](delay, cards, configuredOpenSlots, name) {
		override type TransferSignal = Channel.TransferLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		override type PullSignal = Channel.PulledLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
			new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
			new Channel.PulledLoadImpl(ld, idx, this.name) with BidirectionalCrossSwitch.CrossSwitchSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
			new transport.Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
	}

	class LiftSorterChannel(override val delay: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name:String)
		extends Channel[MaterialLoad, BidirectionalCrossSwitch.CrossSwitchSignal, UnitSorterSignal](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
			new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
			new Channel.PulledLoadImpl(ld, idx, this.name) with UnitSorterSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
			new transport.Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal
	}

	class ShuttleLiftChannel(override val delay: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name:String)
		extends Channel[MaterialLoad, Shuttle.ShuttleSignal, BidirectionalCrossSwitch.CrossSwitchSignal](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with BidirectionalCrossSwitch.CrossSwitchSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Shuttle.ShuttleSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
			new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
			new Channel.PulledLoadImpl(ld, idx, this.name) with BidirectionalCrossSwitch.CrossSwitchSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
			new transport.Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal
	}


	class InboundInductChannel(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with UnitSorterSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundDischargeChannel(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
	}


	def buildAisle[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeSinkSignal >: ChannelConnections.ChannelDestinationMessage]
	(name: String,
	 liftPhysics: Carriage.CarriageTravel,
	 shuttlePhysics: Carriage.CarriageTravel,
	 aisleDepth: Int,
	 inductChannel: (Int, Channel.Ops[MaterialLoad, InductSourceSignal, BidirectionalCrossSwitch.CrossSwitchSignal]),
	 dischargeChannel: (Int, Channel.Ops[MaterialLoad, BidirectionalCrossSwitch.CrossSwitchSignal, DischargeSinkSignal]),
	 shuttles: Seq[Int],
	 align: Int)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): (Processor.Ref, Seq[(Int, Processor.Ref)]) = {
		val liftShuttles =
			shuttles.map{idx =>
				val inboundChannel = Channel.Ops(new LiftShuttleChannel(() => Some(5), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_in"))
				val outboundChannel = Channel.Ops(new ShuttleLiftChannel(() => Some(5), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_out"))
				val config = Shuttle.Configuration(s"shuttle_${name}_$idx", aisleDepth, shuttlePhysics, Seq(inboundChannel), Seq(outboundChannel))
				(ShuttleBuilder.build(config), config.inbound.map(o => (idx, o)), config.outbound.map(o => (idx, o)))
			}
		val inboundDischarge = liftShuttles.flatMap{_._2}
		val outboundInduct = liftShuttles.flatMap{_._3}
		val liftConfig = BidirectionalCrossSwitch.Configuration(name, liftPhysics, Seq(inductChannel), inboundDischarge, outboundInduct, Seq(dischargeChannel), align)
		(LiftBuilder.build(liftConfig), liftShuttles.map(t => t._2.head._1 -> t._1 ))
	}
}

class ShuttleLiftSorterFlowSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {

	implicit val testKit = ActorTestKit()

	override def beforeAll: Unit = {}

	override def afterAll: Unit = testKit.shutdownTestKit()

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	implicit val globalClock = testKit.spawn(Clock())
	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref

	val systemManagerProbe = testKit.createTestProbe[(Clock.Tick, EquipmentManager.Notification)]
	val systemManagerProcessor = new ProcessorSink(systemManagerProbe.ref, globalClock)
	val systemManager = testKit.spawn(systemManagerProcessor.init, "XCManager")

	import ShuttleLiftSorterFlowSpec._

	"A GTP BackEnd" should {
		val liftPhysics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)
		val shuttlePhysics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)

		val sorterAisleA = Channel.Ops(new SorterLiftChannel(() => Some(20), Set("c1", "c2", "c3", "c4", "c5"), 1, s"sorter_aisle_A"))
		val sorterAisleB = Channel.Ops(new SorterLiftChannel(() => Some(20), Set("c1", "c2", "c3", "c4", "c5"), 1, s"sorter_aisle_B"))
		val aisleASorter = Channel.Ops(new LiftSorterChannel(() => Some(20), Set("c1", "c2", "c3", "c4", "c5"), 1, s"aisle_sorter_A"))
		val aisleBSorter = Channel.Ops(new LiftSorterChannel(() => Some(20), Set("c1", "c2", "c3", "c4", "c5"), 1, s"aisle_sorter_B"))

		val aisleA = buildAisle("AisleA", liftPhysics, shuttlePhysics, 20, 0 -> sorterAisleA, 0 -> aisleASorter, Seq(2,5), 0)
		val aisleB = buildAisle("AisleB", liftPhysics, shuttlePhysics, 20, 0 -> sorterAisleB, 0 -> aisleBSorter, Seq(2,5), 0)
		val aisleInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = Map(45 -> aisleASorter, 0 -> aisleBSorter)
		val aisleDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(35 -> sorterAisleA, 40 -> sorterAisleB)

		val chIb1 = new InboundInductChannel(() => Some(10L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundInductChannel(() => Some(10L), Set("Ib1_c1"), 1, "Inbound2")
		val inboundInducts: Map[Int, Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal]] = Map(30 -> new Channel.Ops(chIb1), 45 -> new Channel.Ops(chIb2))

		val chDis1 = new OutboundDischargeChannel(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge_1")
		val chDis2 = new OutboundDischargeChannel(() => Some(10L), Set("Ob2_c1", "Ob2_c2"), 1, "Discharge_2")
		val outboundDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(15 -> new Channel.Ops(chDis1), 30 -> new Channel.Ops(chDis2))

		val sorterInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = inboundInducts ++ aisleInducts
		val sorterDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = outboundDischarges ++ aisleDischarges

		val sorterPhysics = new CircularPathTravel(60, 25, 100)
		val sorterConfig = UnitSorter.Configuration("sorter", 40, 30, sorterInducts, sorterDischarges, sorterPhysics)
		val sorter: Processor.Ref = UnitSorterBuilder.build(sorterConfig)

		val sources = inboundInducts.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal] => new SourceFixture(chOps)(testMonitor, this)}
		val sourceProcessors = sources.zip(Seq("induct_1", "induct_2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceRefs: Seq[Processor.Ref] = sourceProcessors.map(t => testKit.spawn(t.init, t.processorName))

		val destinations =  outboundDischarges.values.toSeq.map{
			case chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType] => new SinkFixture(chOps)(testMonitor, this)
		}
		val destinationProcessors = destinations.zipWithIndex.map{case (dstSink, idx) => new Processor(s"discharge_$idx", globalClock, simController, configurer(dstSink)(testMonitor))}
		val destinationRefs: Seq[Processor.Ref] = destinationProcessors.map(proc => testKit.spawn(proc.init, proc.processorName))



		"A. Configure itself" when {
			"A01. Time is started they register for Configuration" in {
				val actors = sourceRefs ++ destinationRefs ++ Seq(sorter, aisleA._1, aisleB._1) ++ aisleA._2.map(_._2) ++ aisleB._2.map(_._2)
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(actors: _*)
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
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A02. Process the configuration of its elements" in {
				sorter ! Processor.ConfigurationCommand(systemManager, 0L, UnitSorter.NoConfigure)
				systemManagerProbe.expectMessage(0L -> UnitSorter.CompletedConfiguration(sorter))
				val systemManagerProbeExt = new TestProbeExt(systemManagerProbe)
				aisleA._1 ! Processor.ConfigurationCommand(systemManager, 6L, BidirectionalCrossSwitch.NoConfigure)
				aisleB._1 ! Processor.ConfigurationCommand(systemManager, 6L, BidirectionalCrossSwitch.NoConfigure)
				val shuttles = aisleA._2.map(_._2) ++ aisleB._2.map(_._2)
				shuttles.foreach(sh => sh ! Processor.ConfigurationCommand(systemManager, 10L, Shuttle.NoConfigure))

				val shuttleCompletes = shuttles.map(sh => (10L -> Shuttle.CompletedConfiguration(sh))).toList
				systemManagerProbeExt.expectMessages(
					(6L -> BidirectionalCrossSwitch.CompletedConfiguration(aisleA._1)) :: (6L -> BidirectionalCrossSwitch.CompletedConfiguration(aisleB._1)) :: shuttleCompletes: _*
				)
				val actorsToConfigure = mutable.Set((Seq(sorter, aisleA._1, aisleB._1) ++ shuttles): _*)
				var nShuttles = 6
				var nShuttlesToRegister = 6
				simControllerProbe.fishForMessage(1000 millis) {
					case Processor.CompleteConfiguration(pr) if actorsToConfigure.contains(pr) =>
						actorsToConfigure -= pr
						if(actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.CompleteConfiguration if nShuttles > 0 =>
						nShuttles -= 1
						if(actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.RegisterProcessor if nShuttlesToRegister > 0 =>
						println(s"Registering: $msg")
						nShuttlesToRegister -= 1
						if(actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case other => FishingOutcome.Fail(s"Unexpected message: $other with remaining shuttles to register $nShuttlesToRegister and toConfigure $nShuttles")
				}
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceRefs.foreach(act => act ! Processor.ConfigurationCommand(systemManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				destinationRefs.foreach(ref => ref ! Processor.ConfigurationCommand(systemManager, 0L, DownstreamConfigure))
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
		"B. Transfer a load from one of its inbound sources" when {
			val probeLoad = MaterialLoad("FirstLoad")
			val sorterCommand = UnitSorter.Sort(probeLoad, sorterAisleA.ch.name)
			val liftCommand = BidirectionalCrossSwitch.Transfer(sorterAisleA.ch.name, "shuttle_AisleA_2_in")
			val shuttleCommand = Shuttle.Store("shuttle_AisleA_2_in", Carriage.OnLeft(7))
			"B01. Receives commands in advance of the load" in {
				globalClock ! Clock.Enqueue(sorter, Processor.ProcessCommand(systemManager, 64L, sorterCommand))
				globalClock ! Clock.Enqueue(aisleA._1, Processor.ProcessCommand(systemManager, 64L, liftCommand))
				globalClock ! Clock.Enqueue(aisleA._2.head._2, Processor.ProcessCommand(systemManager, 64L, shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
			"B02. The Load is sent to the sortere" in  {
				val probeLoadMessage = TestProbeMessage("FirstLoad", probeLoad)
				sourceRefs.head ! Processor.ProcessCommand(sourceRefs.head, 100000L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
//				systemManagerProbe.expectMessage(65L -> UnitSorter.LoadArrival(probeLoad, chIb1.name))
				systemManagerProbe.expectMessage(134000L -> UnitSorter.CompletedCommand(sorterCommand))
				systemManagerProbe.expectMessage(143000L -> BidirectionalCrossSwitch.CompletedCommand(liftCommand))
				systemManagerProbe.expectMessage(152000L -> Shuttle.CompletedCommand(shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
		}
		/*
		"B. Transfer a load from one collector to the discharge" when {
			val probeLoad = MaterialLoad("First Load")
			"B01. it receives the load in one induct" in {
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceRefs.head ! Processor.ProcessCommand(sourceRefs.head, 55L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				xcManagerProbe.expectMessage(65L -> UnitSorter.LoadArrival(probeLoad, chIb1.name))
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = UnitSorter.Sort(probeLoad, chDis1.name)
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 155, transferCmd))
				testMonitorProbe.expectMessage("Received Load Acknowledgement through Channel: Inbound1 with MaterialLoad(First Load) at 156")
				xcManagerProbe.expectMessage(216L -> UnitSorter.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge_1 at 226")
				destinationRefs.head ! Processor.ProcessCommand(destinationRefs.head, 500, ConsumeLoad)
				testMonitorProbe.expectMessage("Got load Some((MaterialLoad(First Load),Ob1_c2))")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge_1")
			}

 */
	}
}
