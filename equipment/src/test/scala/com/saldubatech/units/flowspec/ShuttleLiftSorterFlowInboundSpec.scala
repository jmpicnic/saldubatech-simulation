/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.flowspec

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.BaseSpec.TestProbeExt
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.UnitsFixture.{DownstreamConfigure, SinkFixture, SourceFixture, TestProbeMessage, UpstreamConfigure, configurer}
import com.saldubatech.units.abstractions.EquipmentManager
import com.saldubatech.units.carriage.{CarriageTravel, OnLeft}
import com.saldubatech.units.lift.XSwitch
import com.saldubatech.units.unitsorter.{CircularPathTravel, UnitSorter, UnitSorterSignal}
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object ShuttleLiftSorterFlowInboundSpec {

}

class ShuttleLiftSorterFlowInboundSpec
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

	import ShuttleLiftSorterFlowOutboundSpec._

	"A GTP BackEnd" should {
		val liftPhysics = new CarriageTravel(2, 6, 4, 8, 8)
		val shuttlePhysics = new CarriageTravel(2, 6, 4, 8, 8)

		val sorterAisleA = Channel.Ops(new SorterLiftChannel(() => Some(20L), () => Some(3), Set("c1", "c2", "c3", "c4", "c5"), 1, s"sorter_aisle_A"))
		val sorterAisleB = Channel.Ops(new SorterLiftChannel(() => Some(20L), () => Some(3), Set("c1", "c2", "c3", "c4", "c5"), 1, s"sorter_aisle_B"))
		val aisleASorter = Channel.Ops(new LiftSorterChannel(() => Some(20L), () => Some(3), Set("c1", "c2", "c3", "c4", "c5"), 1, s"aisle_sorter_A"))
		val aisleBSorter = Channel.Ops(new LiftSorterChannel(() => Some(20L), () => Some(3), Set("c1", "c2", "c3", "c4", "c5"), 1, s"aisle_sorter_B"))

		val aisleA = buildAisle("AisleA", liftPhysics, shuttlePhysics, 20, 0, 0 -> sorterAisleA, 0 -> aisleASorter, Seq(2,5))
		val aisleB = buildAisle("AisleB", liftPhysics, shuttlePhysics, 20, 0, 0 -> sorterAisleB, 0 -> aisleBSorter, Seq(2,5))
		val aisleInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = Map(45 -> aisleASorter, 0 -> aisleBSorter)
		val aisleDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(35 -> sorterAisleA, 40 -> sorterAisleB)

		val chIb1 = new InboundInductChannel(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundInductChannel(() => Some(10L), () => Some(3L), Set("Ib1_c1"), 1, "Inbound2")
		val inboundInducts: Map[Int, Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal]] = Map(30 -> new Channel.Ops(chIb1), 45 -> new Channel.Ops(chIb2))

		val chDis1 = new OutboundDischargeChannel(() => Some(10L), () => Some(3L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge_1")
		val chDis2 = new OutboundDischargeChannel(() => Some(10L), () => Some(3L), Set("Ob2_c1", "Ob2_c2"), 1, "Discharge_2")
		val outboundDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(15 -> new Channel.Ops(chDis1), 30 -> new Channel.Ops(chDis2))

		val sorterInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = inboundInducts ++ aisleInducts
		val sorterDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = outboundDischarges ++ aisleDischarges

		val sorterPhysics = new CircularPathTravel(60, 25, 100)
		val sorterConfig = UnitSorter.Configuration(250, sorterInducts, sorterDischarges, sorterPhysics)
		val sorter: Processor.Ref = UnitSorterBuilder.build("sorter", sorterConfig)

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
				aisleA._1 ! Processor.ConfigurationCommand(systemManager, 6L, XSwitch.NoConfigure)
				aisleB._1 ! Processor.ConfigurationCommand(systemManager, 6L, XSwitch.NoConfigure)
				val shuttles = aisleA._2.map(_._2) ++ aisleB._2.map(_._2)
				shuttles.foreach(sh => sh ! Processor.ConfigurationCommand(systemManager, 10L, Shuttle.NoConfigure))

				val shuttleCompletes = shuttles.map(sh => (10L -> Shuttle.CompletedConfiguration(sh))).toList
				systemManagerProbeExt.expectMessages(
					(6L -> XSwitch.CompletedConfiguration(aisleA._1)) :: (6L -> XSwitch.CompletedConfiguration(aisleB._1)) :: shuttleCompletes: _*
				)
				val actorsToConfigure = mutable.Set((Seq(sorter, aisleA._1, aisleB._1) ++ shuttles): _*)
				simControllerProbe.fishForMessage(1000 millis) {
					case Processor.CompleteConfiguration(pr) if actorsToConfigure.contains(pr) =>
						actorsToConfigure -= pr
						if(actorsToConfigure.nonEmpty) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.CompleteConfiguration =>
						if(actorsToConfigure.nonEmpty) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.RegisterProcessor =>
						if(actorsToConfigure.nonEmpty) FishingOutcome.Continue
						else FishingOutcome.Complete
					case other => FishingOutcome.Fail(s"Unexpected message: $other with remaining actors to configure $actorsToConfigure")
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
			val liftCommand = XSwitch.Transfer(sorterAisleA.ch.name, "shuttle_AisleA_2_in")
			val shuttleCommand = Shuttle.Store("shuttle_AisleA_2_in", OnLeft(7))
			"B01. Receives commands in advance of the load" in {
				globalClock ! Clock.Enqueue(sorter, Processor.ProcessCommand(systemManager, 64L, sorterCommand))
				globalClock ! Clock.Enqueue(aisleA._1, Processor.ProcessCommand(systemManager, 64L, liftCommand))
				globalClock ! Clock.Enqueue(aisleA._2.head._2, Processor.ProcessCommand(systemManager, 64L, shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
			"B02. The Load is sent to the sorter" in  {
				val probeLoadMessage = TestProbeMessage("FirstLoad", probeLoad)
				sourceRefs.head ! Processor.ProcessCommand(sourceRefs.head, 70L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: FirstLoad")
				systemManagerProbe.expectMessage(83L -> UnitSorter.LoadArrival(probeLoad, chIb1.name))
				systemManagerProbe.expectMessage(103L -> UnitSorter.CompletedCommand(sorterCommand))
				systemManagerProbe.expectMessage(146L -> XSwitch.CompletedCommand(liftCommand))
				systemManagerProbe.expectMessage(179L -> Shuttle.CompletedCommand(shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
		}
	}
}
