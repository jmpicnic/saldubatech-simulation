/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.lift

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock.{Delay, Enqueue}
import com.saldubatech.ddes.Processor.Ref
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{Carriage, CarriageNotification}
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object FanInWaitingForSlotSpec {

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	class InboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, FanIn.LiftAssemblySignal](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with FanIn.LiftAssemblySignal

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with FanIn.LiftAssemblySignal
		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, FanIn.LiftAssemblySignal, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx, this.name) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with FanIn.LiftAssemblySignal
	}

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture(ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, FanIn.LiftAssemblySignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySourceMessageType] {

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

	case object ConsumeLoad extends ChannelConnections.DummySinkMessageType

	class SinkFixture(ops: Channel.Ops[MaterialLoad, FanIn.LiftAssemblySignal, ChannelConnections.DummySinkMessageType])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySinkMessageType] {
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
		val channelEnd = ops.registerEnd(sink)

		val runner: Processor.DomainRun[ChannelConnections.DummySinkMessageType] =
			ops.end.loadReceiver orElse {
				ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType] => {
					case ConsumeLoad =>
						val ld = channelEnd.getNext(ctx)
						testMonitor ! s"Got load $ld"
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"SinkFixture: ${ops.ch.name}: Unexpected Message $other")
				}
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

class FanInWaitingForSlotSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import FanInDelayedSlotReleaseSpec._
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

	val carriageMonitorProbe = testKit.createTestProbe[(Clock.Tick, CarriageNotification)]
	val carriageMonitor = carriageMonitorProbe.ref
	val carriageManagerProcessor = new ProcessorSink(carriageMonitor, globalClock)
	val carriageManager = testKit.spawn(carriageManagerProcessor.init, "carriageManager")


	val fanInManagerProbe = testKit.createTestProbe[(Clock.Tick, FanIn.Notification)]
	val fanInManagerRef = fanInManagerProbe.ref
	val fanInManagerProcessor = new ProcessorSink(fanInManagerRef, globalClock)
	val fanInManager = testKit.spawn(fanInManagerProcessor.init, "FanInManager")


	"A Lift Level" should {

		val physics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)
		val carriageProcessor = Carriage.buildProcessor("carriage", physics, globalClock, simController)

		// Channels
		val chIb1 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1"), 1, "Inbound2")
		val ib = Seq(Carriage.Slot(Carriage.OnLeft(0)) -> chIb1, Carriage.Slot(Carriage.OnLeft(1)) -> chIb2)

		val discharge = Carriage.Slot(Carriage.OnLeft(-1)) -> new OutboundChannelImpl(() => Some(10L), Set("Ob1_c1"), 1, "Discharge")

		val carriage: Processor.Ref = testKit.spawn(carriageProcessor.init, "carriage")

		val config = FanIn.Configuration(carriage, ib, discharge)

		// Sources & sinks
		val sources = config.collectorOps.map(ibOps => new FanInDelayedSlotReleaseSpec.SourceFixture(ibOps)(testMonitor, this))
		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))
		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))

		val dischargeSink =  new FanInDelayedSlotReleaseSpec.SinkFixture(config.dischargeOps)(testMonitor, this)
		val dischargeProcessor: Processor[ChannelConnections.DummySinkMessageType] = new Processor("discharge", globalClock, simController, configurer(dischargeSink)(testMonitor))
		val dischargeActor = testKit.spawn(dischargeProcessor.init, "discharge")

		val fanInProcessor = FanIn.buildProcessor("underTest", config)
		val underTest = testKit.spawn(fanInProcessor.init, "underTest")


		"A. Register Itself for configuration" when {

			"A01. Time is started they register for Configuration" in {
				val actorsToRegister: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ Seq(dischargeActor, underTest, carriage): _*)
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
				globalClock ! Clock.Enqueue(carriage, Processor.ConfigurationCommand(carriageManager, 0L, Carriage.Configure(discharge._1)))
				carriageMonitorProbe.expectMessage(0L -> Carriage.CompleteConfiguration(carriage))
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(carriage))
				underTest ! Processor.ConfigurationCommand(fanInManager, 0L, FanIn.NoConfigure)
				simControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				fanInManagerProbe.expectMessage(0L -> FanIn.CompletedConfiguration(underTest))
				fanInManagerProbe.expectNoMessage(500 millis)
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(fanInManager, 0L, FanInDelayedSlotReleaseSpec.UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${FanInDelayedSlotReleaseSpec.UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${FanInDelayedSlotReleaseSpec.UpstreamConfigure}")
				dischargeActor ! Processor.ConfigurationCommand(fanInManager, 0L, FanInDelayedSlotReleaseSpec.DownstreamConfigure)
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
		val firstLoad = MaterialLoad("First Load")
		"B. Transfer a load from one collector to the discharge" when {
			"B01. it receives the load in one channel" in {
				val probeLoadMessage = TestProbeMessage("First Load", firstLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 2L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: First Load")
				fanInManagerProbe.expectMessage(12L -> FanIn.LoadArrival(chIb1.name, firstLoad))
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(First Load)")
				testMonitorProbe.expectNoMessage(500 millis)
				fanInManagerProbe.expectNoMessage(500 millis)
			}
			"B02. and then it receives a Transfer command" in {
				val transferCommand = FanIn.Transfer(chIb1.name)
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(fanInManager, 155, transferCommand))
				fanInManagerProbe.expectMessage(177L -> FanIn.CompletedCommand(transferCommand))
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) arrived to Sink via channel Discharge")
				testMonitorProbe.expectNoMessage(500 millis)
				fanInManagerProbe.expectNoMessage(500 millis)
			}
		}
		"C. Transfer a second load from one collector to the discharge" when {
			val secondTransferCommand = FanIn.Transfer(chIb1.name)
			val thirdTransferCommand = FanIn.Transfer(chIb1.name)
			"C01. it receives the command first" in {
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(fanInManager, 190L, secondTransferCommand))
				testMonitorProbe.expectNoMessage(500 millis)
				fanInManagerProbe.expectNoMessage(500 millis)
			}
			"C02. and then receives the load in the origin channel, waiting for a free discharge card" in {
				val probeLoad = MaterialLoad("Second Load")
				val probeLoadMessage = TestProbeMessage("Second Load", probeLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 240L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Second Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Second Load)")
//				fanInManagerProbe.expectMessage(269L -> FanIn.CompletedCommand(secondTransferCommand))
				testMonitorProbe.expectNoMessage(500 millis)// Load is not received.
				fanInManagerProbe.expectNoMessage(500 millis)
			}
			"C03. One more load to force the shuttle to error out and the FanIn to waitforslot" in {
				val thirdLoad = MaterialLoad("Third Load")
				val probeLoadMessage = TestProbeMessage("Third Load", thirdLoad)
				sourceActors.head ! Processor.ProcessCommand(sourceActors.head, 275L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: Third Load")
				testMonitorProbe.expectMessage("Received Load Acknoledgement at Channel: Inbound1 with MaterialLoad(Third Load)")
				fanInManagerProbe.expectMessage(285L -> FanIn.LoadArrival("Inbound1", thirdLoad))
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(fanInManager, 288L, thirdTransferCommand))
				fanInManagerProbe.expectMessage(288L -> FanIn.FailedBusy(thirdTransferCommand, "Command cannot be processed. Processor is Busy"))
				testMonitorProbe.expectNoMessage(500 millis)
				fanInManagerProbe.expectNoMessage(500 millis)
			}
			"C04. and then the discharge consumes load, second load is sent and third command is complete" in {
				globalClock ! Enqueue(dischargeActor, Processor.ProcessCommand(dischargeActor, 305L, ConsumeLoad))
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(First Load),Ob1_c1))")
				testMonitorProbe.expectMessage("Load MaterialLoad(First Load) released on channel Discharge")
				testMonitorProbe.expectMessage("Load MaterialLoad(Second Load) arrived to Sink via channel Discharge")
				fanInManagerProbe.expectMessage(313L -> FanIn.CompletedCommand(secondTransferCommand))
				globalClock ! Enqueue(dischargeActor, Processor.ProcessCommand(dischargeActor, 325L, ConsumeLoad))
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(Second Load),Ob1_c1))")
				testMonitorProbe.expectMessage("Load MaterialLoad(Second Load) released on channel Discharge")
				globalClock ! Clock.Enqueue(underTest, Processor.ProcessCommand(fanInManager, 330L, thirdTransferCommand))
				testMonitorProbe.expectMessage("Load MaterialLoad(Third Load) arrived to Sink via channel Discharge")
				fanInManagerProbe.expectMessage(352L -> FanIn.CompletedCommand(thirdTransferCommand))
				globalClock ! Enqueue(dischargeActor, Processor.ProcessCommand(dischargeActor, 365L, ConsumeLoad))
				testMonitorProbe.expectMessage(s"Got load Some((MaterialLoad(Third Load),Ob1_c1))")
				testMonitorProbe.expectMessage("Load MaterialLoad(Third Load) released on channel Discharge")
				testMonitorProbe.expectNoMessage(500 millis)
				fanInManagerProbe.expectNoMessage(500 millis)
			}
		}
	}
}
