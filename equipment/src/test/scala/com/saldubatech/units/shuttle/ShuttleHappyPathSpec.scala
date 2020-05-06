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
import com.saldubatech.protocols.{Equipment, EquipmentManagement}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.carriage.{CarriageTravel, OnLeft, OnRight, SlotLocator}
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object ShuttleHappyPathSpec {

	trait DownstreamSignal extends Equipment.MockSinkSignal
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends Equipment.MockSourceSignal
	case object UpstreamConfigure extends Identification.Impl() with UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends Identification.Impl() with UpstreamSignal

	class InboundChannelImpl(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.MockSourceSignal, Equipment.ShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.ShuttleSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.ShuttleSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.MockSourceSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): Channel.TransferLoad[MaterialLoad] = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.ShuttleSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.ShuttleSignal

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.ShuttleSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.ShuttleSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): Channel.AcknowledgeLoad[MaterialLoad] = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSourceSignal
	}

	class OutboundChannelImpl(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, Equipment.ShuttleSignal, Equipment.MockSinkSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.MockSinkSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.MockSinkSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.ShuttleSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSinkSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.MockSinkSignal

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.MockSinkSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.MockSinkSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.ShuttleSignal
	}

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture(ops: Channel.Ops[MaterialLoad, Equipment.MockSourceSignal, Equipment.ShuttleSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSourceSignal] {

		lazy val source = new Channel.Source[MaterialLoad, Equipment.MockSourceSignal] {
			override lazy val ref: Ref = _ref.head

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, Equipment.MockSourceSignal], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[Equipment.MockSourceSignal]): Processor.DomainRun[Equipment.MockSourceSignal] = {
				log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				testMonitor ! s"Received Load Acknoledgement at Channel: ${chStart.channelName} with $load"
				runner
			}
		}
		ops.registerStart(source)

		val runner: Processor.DomainRun[Equipment.MockSourceSignal] =
			ops.start.ackReceiver orElse {
				implicit ctx: Processor.SignallingContext[Equipment.MockSourceSignal] => {
					case TestProbeMessage(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testMonitor ! s"FromSender: $msg"
						if(!ops.start.send(load)) throw new IllegalStateException(s"Channel not free to receive $load")
						log.info(s"Sent $load through channel ${ops.start.channelName}")
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				}
			}
	}


	class SinkFixture(ops: Channel.Ops[MaterialLoad, Equipment.ShuttleSignal, Equipment.MockSinkSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSinkSignal] {
		val sink = new Channel.Sink[MaterialLoad, Equipment.MockSinkSignal] {
			override lazy val ref: Ref = _ref.head


			override def loadArrived(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				testMonitor ! s"Load $load arrived via channel ${endpoint.channelName}"
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		ops.registerEnd(sink)

		val runner: Processor.DomainRun[Equipment.MockSinkSignal] =
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

class ShuttleHappyPathSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	import ShuttleHappyPathSpec._
	override val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

//	implicit val globalClock = testKit.spawn(Clock())
	val testControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = testControllerProbe.ref

	val shuttleLevelManagerProbe = testKit.createTestProbe[(Clock.Tick, EquipmentManagement.ShuttleNotification)]
	val shuttleLevelManagerRef = shuttleLevelManagerProbe.ref
	val shuttleLevelManagerProcessor = new ProcessorSink(shuttleLevelManagerRef, clock)
	val shuttleLevelManager = testKit.spawn(shuttleLevelManagerProcessor.init, "ShuttleLevelManager")


	"A Shuttle Level" should {

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		val initialInventory: Map[SlotLocator, MaterialLoad] = Map(
			OnLeft(2) -> MaterialLoad("L2"),
			OnRight(5) -> MaterialLoad("R5")
		)
		val initial = Shuttle.InitialState(0, initialInventory)


		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(15L), () => Some(3), Set("Ib1_c1", "Ib1_c2"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(15L), () => Some(3), Set("Ib2_c1", "Ib2_c2"), 1, "Inbound2")
		val ib = Seq(chIb1, chIb2).map(Channel.Ops(_))

		val chOb1 = new OutboundChannelImpl(() => Some(15L), () => Some(3), Set("Ob1_c1", "Ob1_c2"), 1, "Outbound1")
		val chOb2 = new OutboundChannelImpl(() => Some(15L), () => Some(3), Set("Ob2_c1", "Ob2_c2"), 1, "Outbound2")
		val ob = Seq(chOb1, chOb2).map(Channel.Ops(_))

		val config = Shuttle.Configuration("underTest", 20, physics, ib, ob)

		// Sources & sinks
		val sources = config.inbound.map(ibOps => new SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val sinks = config.outbound.map(obOps => new SinkFixture(obOps)(testMonitor, this))
		val sinkProcessors = sinks.zip(Seq("d1", "d2")).map(t => new Processor(t._2, clock, simController, configurer(t._1)(testMonitor)))
		val sinkActors = sinkProcessors.zip(Seq("d1", "d2")).map(t => testKit.spawn(t._1.init, t._2))

		implicit val clk = clock
		val shuttleLevelProcessor = Shuttle.buildProcessor(config, initial)
		val underTest = testKit.spawn(shuttleLevelProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ sinkActors ++ Seq(underTest): _*)
				startTime(0L)
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
			}
			"A02. Register its Lift when it gets Configured" in {
				enqueueConfigure(underTest, shuttleLevelManager, 0L, Shuttle.NoConfigure)
				testControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				val msg = shuttleLevelManagerProbe.receiveMessage()
				msg should be(0L -> Shuttle.CompletedConfiguration(underTest))
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => enqueueConfigure(act, shuttleLevelManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${UpstreamConfigure}")
				sinkActors.foreach(act => enqueueConfigure(act, shuttleLevelManager, 0L, DownstreamConfigure))
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
		"B. Notify receiving a load through an inbound channel" when {
			"B01. it has just started" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				enqueue(sourceActors.head, sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				shuttleLevelManagerProbe.expectMessage(20L -> Shuttle.LoadArrival(chIb1.name, probeLoad))
			}
			"B02. Don't notify when a second load on the same channel is Queued" in {
				val probeLoad = MaterialLoad("Second Load")
				val probeLoadMessage = TestProbeMessage("Second Load", probeLoad)
				enqueue(sourceActors.head, sourceActors.head, 22L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Second Load")
				shuttleLevelManagerProbe.expectNoMessage(500 millis)
			}
		}
		"C. Store the load in a location" when {
			"C01. receiving a Store Command" in {
				val storeCmd = Shuttle.Store("Inbound1", OnRight(4))
				enqueue(underTest, shuttleLevelManager, 100L, storeCmd)
				shuttleLevelManagerProbe.expectMessage((115L -> Shuttle.LoadArrival("Inbound1",MaterialLoad("Second Load"))))
				shuttleLevelManagerProbe.expectMessage((128L -> Shuttle.CompletedCommand(storeCmd)))
			}
			"C02. And then move it to a different location with a Groom Command" in {
				val groomCmd = Shuttle.Groom(OnRight(4), OnLeft(7))
				log.info(s"Queuing Groom Command: $groomCmd")
				enqueue(underTest, shuttleLevelManager, 130L, groomCmd)
				shuttleLevelManagerProbe.expectMessage((151L -> Shuttle.CompletedCommand(groomCmd)))
			}
			"C03. And finally send it through an outbound channel with a Retrieve Command" in {
				val retrieveCmd = Shuttle.Retrieve(OnLeft(7), "Outbound2")
				log.info(s"Queuing Retrieve Command: $retrieveCmd")
				enqueue(underTest, shuttleLevelManager, 155, retrieveCmd)
				shuttleLevelManagerProbe.expectMessage((180L -> Shuttle.CompletedCommand(retrieveCmd)))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived via channel Outbound2")
			}
		}
	}
}
