/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.shuttle


import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor.ProcessorRef
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.shuttle
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object ShuttleLevelSpec {

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	class InboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, ShuttleLevel.ShuttleLevelMessage](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ShuttleLevel.ShuttleLevelMessage {
			override def toString = s"Receiver.TransferLoad(ch: $channel, ld: $load, rs: $resource)"
		}


		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx) with ShuttleLevel.ShuttleLevelMessage {
			override def toString = s"Receiver.PulledLoad(load: $ld, idx: $idx)"
		}

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType {
			override def toString = s"Sender.AcknowledgeLoad(ch: $channel, ld: $load, rs: $resource)"
		}
	}

	class OutboundChannelImpl(delay: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, ShuttleLevel.ShuttleLevelMessage, ChannelConnections.DummySinkMessageType](delay, cards, configuredOpenSlots, name) {
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType {
			override def toString = s"Receiver.TransferLoad(ch: $channel, ld: $load, rs: $resource)"
		}

		override def loadPullBuilder(ld: MaterialLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, idx) with ChannelConnections.DummySinkMessageType {
			override def toString = s"Receiver.PulledLoad(load: $ld, idx: $idx)"
		}

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ShuttleLevel.ShuttleLevelMessage {
			override def toString = s"Sender.AcknowledgeLoad(ch: $channel, ld: $load, rs: $resource)"
		}
	}

	class SourceFixture(host: ProcessorRef, ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, ShuttleLevel.ShuttleLevelMessage])(testMonitor: ActorRef[String], hostTest: WordSpec) extends LogEnabled {
		lazy val source = new Channel.Source[MaterialLoad, ChannelConnections.DummySourceMessageType] {
			override lazy val ref = host

			override def loadAcknowledged(load: MaterialLoad)(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType]): Processor.DomainRun[ChannelConnections.DummySourceMessageType] = {
				testMonitor ! s"Received Load Acknoledgement at Channel: ${ops.ch.name} with $load"
				runner
			}
		}
		ops.registerStart(source)

		val runner: Processor.DomainRun[ChannelConnections.DummySourceMessageType] = {
			implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType] =>
				(ops.start.ackReceiver orElse Processor.DomainRun[ChannelConnections.DummySourceMessageType] {
					case TestProbeMessage(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testMonitor ! s"FromSender: $msg"
						ops.start.send(load)
						log.info(s"Sent $load through channel ${ops.start.channelName}")
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				})(ctx)
		}
	}

	class SinkFixture(host: ProcessorRef, ops: Channel.Ops[MaterialLoad, ShuttleLevel.ShuttleLevelMessage, ChannelConnections.DummySinkMessageType])(testMonitor: ActorRef[String], hostTest: WordSpec) extends LogEnabled {
		val sink = new Channel.Sink[MaterialLoad, ChannelConnections.DummySinkMessageType] {
			override lazy val ref = host

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load arrived via channel ${endpoint.channelName}"
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		ops.registerEnd(sink)
		val runner: Processor.DomainRun[ChannelConnections.DummySinkMessageType] = ops.end.loadReceiver orElse Processor.DomainRun {
			case other =>
				log.info(s"Received Other Message at Receiver: $other")
				hostTest.fail(s"Unexpected Message $other")
		}
	}

	def configurer[DomainMessage](runner: Processor.DomainRun[DomainMessage])(monitor: ActorRef[String]) =
		new Processor.DomainConfigure[DomainMessage] {
			override def configure(config: DomainMessage)(implicit ctx: Processor.SignallingContext[DomainMessage]): Processor.DomainRun[DomainMessage] = {
					monitor ! s"Received Configuration: $config"
					runner
			}
		}

}

class ShuttleLevelSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import ShuttleLevelSpec._
	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	val up1 = testKit.createTestProbe[Processor.ProcessorMessage]
	val up2  = testKit.createTestProbe[Processor.ProcessorMessage]
	val dp1 = testKit.createTestProbe[Processor.ProcessorMessage]
	val dp2 = testKit.createTestProbe[Processor.ProcessorMessage]

	implicit val globalClock = testKit.spawn(Clock())
	val testControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = testControllerProbe.ref

	val shuttleLevelManagerProbe = testKit.createTestProbe[Processor.ProcessorMessage]
	val shuttleLevelManager = shuttleLevelManagerProbe.ref

	"A Shuttle Level" should {

		val physics = new Shuttle.ShuttleTravel(2, 6, 4, 8, 8)
		val shuttleProcessor = Shuttle.buildProcessor("shuttle", physics, globalClock, simController)


		val initialInventory: Map[Shuttle.LevelLocator, MaterialLoad] = Map(
			Shuttle.OnLeft(2) -> MaterialLoad("L2"),
			Shuttle.OnRight(5) -> MaterialLoad("R5")
		)
		val initial = ShuttleLevel.InitialState(Shuttle.OnRight(0), initialInventory)

		val chIb1 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1", "Ib1_c2"), 1, "Inbound1")
		val chIb2 = new InboundChannelImpl(() => Some(10L), Set("Ib1_c1", "Ib1_c2"), 1, "Inbound2")
		val ib = Seq(chIb1, chIb2)

		val chOb1 = new OutboundChannelImpl(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Outbound1")
		val chOb2 = new OutboundChannelImpl(() => Some(10L), Set("Ob2_c1", "Ob2_c2"), 1, "Outbound2")
		val ob = Seq(chOb1, chOb2)

		val config = ShuttleLevel.Configuration(20, ib, ob)

		// Sources
		val sources = config.inboundOps.zip(Seq(up1, up2)).map(t => new ShuttleLevelSpec.SourceFixture(t._2.ref, t._1)(testMonitor, this))
		val sinks = config.outboundOps.zip(Seq(dp1, dp2)).map(t => new ShuttleLevelSpec.SinkFixture(t._2.ref, t._1)(testMonitor, this))

		val sourceProcessors = sources.zip(Seq("u1", "u2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1.runner)(testMonitor)))
		val sinkProcessors = sinks.zip(Seq("d1", "d2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1.runner)(testMonitor)))

		val shuttleLevelProcessor = ShuttleLevel.buildProcessor("underTest", shuttleProcessor, config, initial)
		val underTest = testKit.spawn(shuttleLevelProcessor.init, "underTest")

		val sourceActors = sourceProcessors.zip(Seq("u1", "u2")).map(t => testKit.spawn(t._1.init, t._2))
		val sinkActors = sinkProcessors.zip(Seq("d1", "d2")).map(t => testKit.spawn(t._1.init, t._2))


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
			"A02. Register its Shuttle when it gets Configured" in {
				underTest ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, ShuttleLevel.NoConfigure)
				testControllerProbe.expectMessageType[Processor.RegisterProcessor] // SHuttle ref is not available here.
				testControllerProbe.expectMessageType[Processor.CompleteConfiguration]
				testControllerProbe.expectMessage(Processor.CompleteConfiguration(underTest))
				testControllerProbe.expectNoMessage(500 millis)
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceActors.foreach(act => act ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, ShuttleLevelSpec.UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${ShuttleLevelSpec.UpstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${ShuttleLevelSpec.UpstreamConfigure}")
				sinkActors.foreach(act => act ! Processor.ConfigurationCommand(shuttleLevelManager, 0L, ShuttleLevelSpec.DownstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: ${ShuttleLevelSpec.DownstreamConfigure}")
				testMonitorProbe.expectMessage(s"Received Configuration: ${ShuttleLevelSpec.DownstreamConfigure}")
				testMonitorProbe.expectNoMessage(500 millis)
				val actorsToConfigure: mutable.Set[ActorRef[Processor.ProcessorMessage]] = mutable.Set(sourceActors ++ sinkActors: _*)
				log.info(s"Actors to Configure: $actorsToConfigure")
				testControllerProbe.fishForMessage(3 second) {
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
				testControllerProbe.expectNoMessage(500 millis)
			}
		}
		"B. Allow sending a load through an inbound channel" when {
			"B01. it has just started" in {
				val probeLoad = TestProbeMessage("First Load", MaterialLoad("First Load"))
				sourceActors(0) ! Processor.ProcessCommand(sourceActors(0), 2L, probeLoad)

			}
		}
	}


}
