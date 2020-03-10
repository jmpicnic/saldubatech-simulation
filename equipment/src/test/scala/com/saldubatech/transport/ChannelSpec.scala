/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes
import com.saldubatech.ddes.Processor.SignallingContext
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.test.BaseSpec
import com.saldubatech.transport.ChannelConnections._
import org.apache.commons.math3.ode.sampling.DummyStepHandler
import scalaz.Heap.Empty

import scala.concurrent.duration._

object ChannelSpec {
	case class ProbeLoad(lid: String) extends Identification.Impl(lid)

	sealed trait SenderType extends DummySourceMessageType
	sealed trait ReceiverType extends DummySinkMessageType

	case class SenderConfigType(id: String) extends SenderType
	case class SenderProcessType(id: String, l: ProbeLoad) extends SenderType

	case class ReceiverConfigType(id: String) extends ReceiverType
	case class ReceiverProcessType(id: String, load: ProbeLoad) extends ReceiverType

}

class ChannelSpec extends BaseSpec {
	import ChannelSpec._

	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}




	val testActor = testKit.createTestProbe[String]
	val globalClock = testKit.spawn(Clock())
	val testController = testKit.createTestProbe[ControllerMessage]
	val underTest = new Channel[ProbeLoad, DummySourceMessageType, DummySinkMessageType](() => Some(7), Set("card1", "card2"), 1, "underTest"){
		override def transferBuilder(channel: String, load: ProbeLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[ProbeLoad](channel, load, resource) with DummySinkMessageType {
			override def toString = s"Receiver.TransferLoad(ch: $channel, ld: $load, rs: $resource)"
		}

		override def loadPullBuilder(ld: ProbeLoad, idx: Int): PullSignal = new Channel.PulledLoadImpl[ProbeLoad](ld, idx) with DummySinkMessageType {
			override def toString = s"Receiver.PulledLoad(load: $ld, idx: $idx)"
		}

		override def acknowledgeBuilder(channel: String, load: ProbeLoad, resource: String): AckSignal = new Channel.AckLoadImpl[ProbeLoad](channel, load, resource)  with DummySourceMessageType {
			override def toString = s"Sender.AcknowledgeLoad(ch: $channel, ld: $load, rs: $resource)"
		}
	}
	implicit object channelOps extends Channel.Ops[ProbeLoad, DummySourceMessageType, DummySinkMessageType](underTest)

	def source(host: Processor.ProcessorRef):Channel.Source[ProbeLoad, DummySourceMessageType] = new Channel.Source[ProbeLoad, DummySourceMessageType]{
		override lazy val ref = host
		override def loadAcknowledged(load: ProbeLoad)(implicit ctx: SignallingContext[DummySourceMessageType]): Processor.DomainRun[DummySourceMessageType] = {
			testActor.ref ! s"${load.lid}-Acknowledged"
			senderRunner
		}
	}

	def sink(host: Processor.ProcessorRef) = new Channel.Sink[ProbeLoad, DummySinkMessageType] {
		override lazy val ref = host
		override def loadArrived(endpoint: Channel.End[ProbeLoad, DummySinkMessageType], load: ProbeLoad, at: Option[Int])(implicit ctx: SignallingContext[DummySinkMessageType]): Processor.DomainRun[DummySinkMessageType] = {
			log.info(s"Called loadArrived with $load")
			testActor.ref ! s"${load.lid}-Received";
			val recovered = endpoint.get(load)
			recovered should not be Empty
			recovered.head._1 should be (load)
			receiverRunner
		}

		override def loadReleased(endpoint: Channel.End[ProbeLoad, DummySinkMessageType], load: ProbeLoad, at: Option[Int])(implicit ctx: SignallingContext[DummySinkMessageType]): Processor.DomainRun[DummySinkMessageType] = {
			log.info(s"After Load Release $load")
			testActor.ref ! "probe1-release"
			endpoint.loadReceiver orElse Processor.DomainRun {
				case other =>
					log.info(s"Received Other Message at Receiver: $other")
					fail(s"Unexpected Message $other")
					receiverRunner
			}
		}
	}


	def senderConfigurer(implicit ops: Channel.Ops[ProbeLoad, DummySourceMessageType, DummySinkMessageType]) =
		new Processor.DomainConfigure[DummySourceMessageType] {
			override def configure(config: DummySourceMessageType)(implicit ctx: Processor.SignallingContext[DummySourceMessageType]): Processor.DomainRun[DummySourceMessageType] = config match {
				case SenderConfigType(msg) =>
					ops.registerStart(source(ctx.aCtx.self));
					testActor.ref ! msg
					senderRunner
			}
		}

	def senderRunner(implicit ops: Channel.Ops[ProbeLoad, DummySourceMessageType, DummySinkMessageType], ctx: SignallingContext[DummySourceMessageType]): Processor.DomainRun[DummySourceMessageType] =
		ops.start.ackReceiver orElse Processor.DomainRun[DummySourceMessageType]{
			case SenderProcessType(msg, load) =>
				log.info(s"Got Domain Message in Sender $msg")
				testActor.ref ! s"FromSender: $msg"
				ops.start.send(load)
				log.info(s"Sent $load through channel ${ops.start.channelName}")
				senderRunner
			case other =>
				fail(s"Unexpected Message $other");
				senderRunner
		}


	val sender = new Processor("sender", globalClock, testController.ref, senderConfigurer)


	def receiverRunner(implicit ops: Channel.Ops[ProbeLoad, DummySourceMessageType, DummySinkMessageType], ctx: SignallingContext[DummySinkMessageType]): Processor.DomainRun[DummySinkMessageType] =
		ops.end.loadReceiver orElse Processor.DomainRun {
			case other =>
				log.info(s"Received Other Message at Receiver: $other")
				fail(s"Unexpected Message $other")
				receiverRunner
		}


	def receiverConfigurer(implicit ops: Channel.Ops[ProbeLoad, DummySourceMessageType, DummySinkMessageType]): Processor.DomainConfigure[DummySinkMessageType] =
		new Processor.DomainConfigure[DummySinkMessageType] {
			override def configure(config: DummySinkMessageType)(implicit ctx: Processor.SignallingContext[DummySinkMessageType]): Processor.DomainRun[DummySinkMessageType] = config match {
				case ReceiverConfigType(msg) =>
					ops.registerEnd(sink(ctx.aCtx.self))
					testActor.ref ! s"From Receiver-Cfg: $msg"
					log.info(s"Configured Receiver and installing its Runner")
					receiverRunner
			}
		}

	val receiver = new Processor("receiver", globalClock, testController.ref, receiverConfigurer)

	val mockProcessorOrigin = testKit.createTestProbe[Processor.ProcessorMessage]


	"A Channel" when {
		"A. Initialized with a capacity of 2 and no Lookup" should {
			val s = testKit.spawn(sender.init, "Sender")
			val r = testKit.spawn(receiver.init, "Receiver")
			"A1. Allow for Start and End registration" in {
				s ! Processor.ConfigurationCommand[SenderConfigType](mockProcessorOrigin.ref, 0L, SenderConfigType("ConfigureSender"))
				testActor.expectMessage("ConfigureSender")
				r ! Processor.ConfigurationCommand[ReceiverConfigType](mockProcessorOrigin.ref, 0L, ReceiverConfigType("ConfigureReceiver"))
				testActor.expectMessage("From Receiver-Cfg: ConfigureReceiver")
				testActor.ref ! "DoneConfiguring"
			}
			"A2: Transfer a load between the sender and receiver with a Delay" in {
				testActor.expectMessage("DoneConfiguring")
				val probe1 = ProbeLoad("probe1")
				val msg1 = Processor.ProcessCommand(mockProcessorOrigin.ref, 5L, SenderProcessType("probe1", probe1))
				globalClock ! Clock.Enqueue(s, 5, msg1)
//				globalClock ! Clock.Enqueue(r, 15, Processor.ProcessCommand(mockProcessorOrigin.ref, 15, ReceiverProcessType("probe1-release", probe1)))

				globalClock ! Clock.StartTime(0L)
				testActor.expectMessage("FromSender: probe1")
				testActor.expectMessage("probe1-Received")
				channelOps.start.availableCards shouldBe 1
				testActor.expectMessage("probe1-release")
				testActor.expectMessage("probe1-Acknowledged")
				testActor.expectNoMessage(500 millis)
				channelOps.start.availableCards shouldBe 2
			}
		}

	}

}
