/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor.Ref
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

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	class InboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, BidirectionalCrossSwitch.CrossSwitchSignal](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with BidirectionalCrossSwitch.CrossSwitchSignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, BidirectionalCrossSwitch.CrossSwitchSignal, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with BidirectionalCrossSwitch.CrossSwitchSignal
	}

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture(ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, BidirectionalCrossSwitch.CrossSwitchSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySourceMessageType] {

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


	class SinkFixture(ops: Channel.Ops[MaterialLoad, BidirectionalCrossSwitch.CrossSwitchSignal, ChannelConnections.DummySinkMessageType])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySinkMessageType] {
		val sink = new Channel.Sink[MaterialLoad, ChannelConnections.DummySinkMessageType] {
			override lazy val ref: Ref = _ref.head


			override def loadArrived(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load arrived to Sink via channel ${endpoint.channelName}"
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

class BidirectionalCrossSwitchSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import BidirectionalCrossSwitchSpec._
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
		val sources = config.outboundInduction.map(_._2).map(ibOps => new BidirectionalCrossSwitchSpec.SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new BidirectionalCrossSwitchSpec.SinkFixture(config.outboundDischarge.head._2)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", globalClock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		val underTestProcessor = BidirectionalCrossSwitch.buildProcessor("underTest", config)
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
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A02. Register its Lift when it gets Configured" in {
				underTest ! Processor.ConfigurationCommand(xcManager, 0L, BidirectionalCrossSwitch.NoConfigure)
				simControllerProbe.expectMessageType[Processor.RegisterProcessor] // SHuttle ref is not available here.
				simControllerProbe.expectMessageType[Processor.CompleteConfiguration]
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				xcManagerProbe.expectMessage(0L -> BidirectionalCrossSwitch.CompletedConfiguration(underTest))
				xcManagerProbe.expectNoMessage(500 millis)
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(xcManager, 0L, FanInDelayedSlotReleaseSpec.UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${FanInDelayedSlotReleaseSpec.UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${FanInDelayedSlotReleaseSpec.UpstreamConfigure}")
				dischargeActor ! Processor.ConfigurationCommand(xcManager, 0L, FanInDelayedSlotReleaseSpec.DownstreamConfigure)
				testMonitorProbe.expectMessage(s"Received Configuration: ${FanInDelayedSlotReleaseSpec.DownstreamConfigure}")
				testMonitorProbe.expectNoMessage(500 millis)
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
				simControllerProbe.expectNoMessage(500 millis)
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
				testMonitorProbe.expectNoMessage(500 millis)
				xcManagerProbe.expectNoMessage(500 millis)
			}
			"B02. and then it receives a Transfer command" in {
				val transferCmd = BidirectionalCrossSwitch.Transfer(chIb1.name, "Discharge")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 155, transferCmd))
				xcManagerProbe.expectMessage(174L -> BidirectionalCrossSwitch.CompletedCommand(transferCmd))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				testMonitorProbe.expectNoMessage(500 millis)
				xcManagerProbe.expectNoMessage(500 millis)
			}
		}
		"C. Transfer a load from one collector to the discharge" when {
			val transferCmd = BidirectionalCrossSwitch.Transfer(chIb1.name, "Discharge")
			"C01. it receives the command first" in {
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(xcManager, 190L, transferCmd))
				testMonitorProbe.expectNoMessage(500 millis)
				xcManagerProbe.expectNoMessage(500 millis)
			}
			"C02. and then receives the load in the origin channel" in {
				val probeLoad = MaterialLoad("First Load")
				val probeLoadMessage = TestProbeMessage("First Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				xcManagerProbe.expectMessage(269L -> BidirectionalCrossSwitch.CompletedCommand(transferCmd))
				testMonitorProbe.expectNoMessage(500 millis)
				xcManagerProbe.expectNoMessage(500 millis)
			}
		}
	}
}
