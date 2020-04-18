/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor.Ref
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{CarriageTravel, OnLeft, OnRight, SlotLocator}
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object ShuttleRejectedCommandsSpec {

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	class InboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, Shuttle.ShuttleSignal](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with Shuttle.ShuttleSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with Shuttle.ShuttleSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Shuttle.ShuttleSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Shuttle.ShuttleSignal, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Shuttle.ShuttleSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal
	}

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture(ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, Shuttle.ShuttleSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySourceMessageType] {

		lazy val source = new Channel.Source[MaterialLoad, ChannelConnections.DummySourceMessageType] {
			override lazy val ref: Ref = _ref.head

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, ChannelConnections.DummySourceMessageType], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType]): Processor.DomainRun[ChannelConnections.DummySourceMessageType] = {
				log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				testMonitor ! s"Received Load Acknoledgement at Channel: ${chStart.channelName} with $load"
				runner
			}
		}
		ops.registerStart(source)

		val runner: Processor.DomainRun[ChannelConnections.DummySourceMessageType] =
			ops.start.ackReceiver orElse {
				implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType] => {
					case TestProbeMessage(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testMonitor ! s"FromSender: $msg"
						ops.start.send(load)
						log.info(s"Sent $load through channel ${ops.start.channelName}")
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				}
			}
	}


	class SinkFixture(ops: Channel.Ops[MaterialLoad, Shuttle.ShuttleSignal, ChannelConnections.DummySinkMessageType])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySinkMessageType] {
		val sink = new Channel.Sink[MaterialLoad, ChannelConnections.DummySinkMessageType] {
			override lazy val ref: Ref = _ref.head


			override def loadArrived(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load arrived via channel ${endpoint.channelName}"
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		ops.registerEnd(sink)

		val runner: Processor.DomainRun[ChannelConnections.DummySinkMessageType] =
			ops.end.loadReceiver orElse Processor.DomainRun {
				case other =>
					log.info(s"Received Other Message at Receiver: $other")
					hostTest.fail(s"SinkFixture: ${ops.ch.name}: Unexpected Message $other")
			}
	}

	def configurer[DomainMessage](fixture: Fixture[DomainMessage])(monitor: ActorRef[String]) =
		new Processor.DomainConfigure[DomainMessage] {
			override def configure(config: DomainMessage)(implicit ctx: Processor.SignallingContext[DomainMessage]): Processor.DomainRun[DomainMessage] = {
				monitor ! s"Received Configuration: $config"
				fixture._ref = Some(ctx.aCtx.self)
				fixture.runner
			}
		}

}

class ShuttleRejectedCommandsSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import ShuttleRejectedCommandsSpec._
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
	val testControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = testControllerProbe.ref

	val shuttleLevelManagerProbe = testKit.createTestProbe[(Clock.Tick, Shuttle.Notification)]
	val shuttleLevelManagerRef = shuttleLevelManagerProbe.ref
	val shuttleLevelManagerProcessor = new ProcessorSink(shuttleLevelManagerRef, globalClock)
	val shuttleLevelManager = testKit.spawn(shuttleLevelManagerProcessor.init, "ShuttleLevelManager")


	"A Shuttle Level" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)


		val initialInventory: Map[SlotLocator, MaterialLoad] = Map(
			OnLeft(2) -> MaterialLoad("L2"),
			OnRight(5) -> MaterialLoad("R5")
		)
		val initial = Shuttle.InitialState(0, initialInventory)


		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1", "Ib1_c2"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1", "Ib1_c2"), 1, "Inbound2")
		val ib = Seq(chIb1, chIb2).map(Channel.Ops(_))

		val chOb1 = new OutboundChannelImpl(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Outbound1")
		val chOb2 = new OutboundChannelImpl(() => Some(10L), Set("Ob2_c1", "Ob2_c2"), 1, "Outbound2")
		val ob = Seq(chOb1, chOb2).map(Channel.Ops(_))

		val config = Shuttle.Configuration("underTest", 20, physics, ib, ob)
		// Sources & sinks
		val sources = config.inbound.map(ibOps => new SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val sinks = config.outbound.map(obOps => new SinkFixture(obOps)(testMonitor, this))
		val sinkProcessors = sinks.zip(Seq("d1", "d2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sinkActors = sinkProcessors.zip(Seq("d1", "d2")).map(t => testKit.spawn(t._1.init, t._2))

		val shuttleLevelProcessor = Shuttle.buildProcessor(config, initial)
		val underTest = testKit.spawn(shuttleLevelProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ sinkActors ++ Seq(underTest): _*)
				globalClock ! Clock.StartTime(0L)
				testControllerProbe.fishForMessage(3 second) {
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
				testControllerProbe.expectNoMessage(500 millis)
			}
			"A02. Register its Lift when it gets Configured" in {
				underTest ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, Shuttle.NoConfigure)
				testControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				val msg = shuttleLevelManagerProbe.receiveMessage()
				msg should be(0L -> Shuttle.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				sinkActors.foreach(act => act ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, DownstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${DownstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${DownstreamConfigure}")
				val actorsToConfigure: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ sinkActors: _*)
				log.info(s"Actors to Configure: $actorsToConfigure")
				testControllerProbe.fishForMessage(500 millis) {
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
		"B. Reply with Error when trying to puaway into Full Locations" when {
			"B01. Storing a Load" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				shuttleLevelManagerProbe.expectMessage(12L -> Shuttle.LoadArrival(chIb1.name, probeLoad))
				val storeCmd = Shuttle.Store("Inbound1", OnLeft(2))
				log.info(s"Queuing Store Command: $storeCmd")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(shuttleLevelManager, 100L, storeCmd))
				shuttleLevelManagerProbe.expectMessage((100L -> Shuttle.FailedEmpty(storeCmd, "Target Location to Store (OnLeft(2)) is Full")))
			}
			"B02. Grooming a load" in {
				val groomCmd = Shuttle.Groom(OnLeft(2), OnRight(5))
				log.info(s"Queuing Groom Command: $groomCmd")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(shuttleLevelManager, 130L, groomCmd))
				shuttleLevelManagerProbe.expectMessage((130L -> Shuttle.FailedEmpty(groomCmd, "Target Location to Store (OnRight(5)) is not empty")))
			}
		}
		"C. Reply Error when retrieving from empty locations" when {
			"C01. Retrieving a load" in {
				val retrieveCmd = Shuttle.Retrieve(OnLeft(7), "Outbound2")
				log.info(s"Queuing Retrieve Command: $retrieveCmd")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(shuttleLevelManager, 155, retrieveCmd))
				shuttleLevelManagerProbe.expectMessage((155L -> Shuttle.FailedEmpty(retrieveCmd, "Source Location (OnLeft(7)) to Retrieve is Empty")))
			}
			"C02. Grooming a Load" in {
				val groomCmd = Shuttle.Groom(OnRight(4), OnLeft(7))
				log.info(s"Queuing Groom Command: $groomCmd")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(shuttleLevelManager, 160, groomCmd))
				shuttleLevelManagerProbe.expectMessage((160L -> Shuttle.FailedEmpty(groomCmd, "Source Location (OnRight(4)) to Retrieve is Empty")))
			}
		}
	}
}
