/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate
import com.saldubatech.ddes.AgentTemplate.{DomainConfigure, DomainRun, Run, SignallingContext}
import com.saldubatech.ddes.Simulation.{ControllerMessage, SimRef, SimSignal}
import com.saldubatech.protocols.Equipment
import com.saldubatech.test.ClockEnabled
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}
import scalaz.Heap.Empty

import scala.concurrent.duration._

object ChannelSpec {
	case class ProbeLoad(lid: String) extends Identification.Impl(lid)

	sealed trait SenderType extends Equipment.MockSourceSignal
	sealed trait ReceiverType extends Equipment.MockSinkSignal

	case class SenderConfigType(id: String) extends Identification.Impl() with SenderType
	case class SenderProcessType(id: String, l: ProbeLoad) extends Identification.Impl() with SenderType

	case class ReceiverConfigType(id: String) extends Identification.Impl() with ReceiverType
	case class ReceiverProcessType(id: String, load: ProbeLoad) extends Identification.Impl() with ReceiverType

}

class ChannelSpec extends AnyWordSpec
	with Matchers
	with AnyWordSpecLike
	with BeforeAndAfterAll
	with LogEnabled
	with ClockEnabled {
	import ChannelSpec._

	override val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}




	val testActor = testKit.createTestProbe[String]

	val testController = testKit.createTestProbe[ControllerMessage]
	val underTest = new Channel[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal](() => Some(7), () => Some(3), Set("card1", "card2"), 1, "underTest"){
		override type TransferSignal = Channel.TransferLoad[ProbeLoad] with Equipment.MockSinkSignal
		override type PullSignal = Channel.PulledLoad[ProbeLoad] with Equipment.MockSinkSignal
		override def transferBuilder(channel: String, load: ProbeLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[ProbeLoad](channel, load, resource) with Equipment.MockSinkSignal {
			override def toString = s"Receiver.TransferLoad(ch: $channel, ld: $load, rs: $resource)"
		}

		override def loadPullBuilder(ld: ProbeLoad, resource: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[ProbeLoad](ld, resource, idx, this.name) with Equipment.MockSinkSignal {
			override def toString = s"Receiver.PulledLoad(load: $ld, idx: $idx)"
		}

		override type DeliverSignal = Channel.DeliverLoadImpl[ProbeLoad] with Equipment.MockSinkSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[ProbeLoad](channel) with Equipment.MockSinkSignal

		override type AckSignal = Channel.AcknowledgeLoad[ProbeLoad] with Equipment.MockSourceSignal
		override def acknowledgeBuilder(channel: String, load: ProbeLoad, resource: String): AckSignal = new Channel.AckLoadImpl[ProbeLoad](channel, load, resource)  with Equipment.MockSourceSignal {
			override def toString = s"Sender.AcknowledgeLoad(ch: $channel, ld: $load, rs: $resource)"
		}
	}
	implicit object channelOps extends Channel.Ops[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal](underTest)

	def source(host: SimRef):Channel.Source[ProbeLoad, Equipment.MockSourceSignal] = new Channel.Source[ProbeLoad, Equipment.MockSourceSignal]{
		override lazy val ref = host
		override def loadAcknowledged(chStart: Channel.Start[ProbeLoad, Equipment.MockSourceSignal], load: ProbeLoad)(implicit ctx: SignallingContext[Equipment.MockSourceSignal]): DomainRun[Equipment.MockSourceSignal] = {
			testActor.ref ! s"${load.lid}-Acknowledged"
			senderRunner
		}
	}

	def sink(host: SimRef) = new Channel.Sink[ProbeLoad, Equipment.MockSinkSignal] {
		override lazy val ref = host
		override def loadArrived(endpoint: Channel.End[ProbeLoad, Equipment.MockSinkSignal], load: ProbeLoad, at: Option[Int])(implicit ctx: SignallingContext[Equipment.MockSinkSignal]): DomainRun[Equipment.MockSinkSignal] = {
			log.info(s"Called loadArrived with $load")
			testActor.ref ! s"${load.lid}-Received";
			val recovered = endpoint.get(load)
			recovered should not be Empty
			recovered.head._1 should be (load)
			receiverRunner
		}

		override def loadReleased(endpoint: Channel.End[ProbeLoad, Equipment.MockSinkSignal], load: ProbeLoad, at: Option[Int])(implicit ctx: SignallingContext[Equipment.MockSinkSignal]): DomainRun[Equipment.MockSinkSignal] = {
			log.info(s"After Load Release $load")
			testActor.ref ! "probe1-release"
			endpoint.loadReceiver orElse DomainRun {
				case other =>
					log.info(s"Received Other Message at Receiver: $other")
					fail(s"Unexpected Message $other")
					receiverRunner
			}
		}
	}


	def senderConfigurer(implicit ops: Channel.Ops[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal]) =
		new DomainConfigure[Equipment.MockSourceSignal] {
			override def configure(config: Equipment.MockSourceSignal)(implicit ctx: SignallingContext[Equipment.MockSourceSignal]): DomainRun[Equipment.MockSourceSignal] = config match {
				case SenderConfigType(msg) =>
					ops.registerStart(source(ctx.aCtx.self));
					testActor.ref ! msg
					senderRunner
			}
		}

	def senderRunner(implicit ops: Channel.Ops[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal]): DomainRun[Equipment.MockSourceSignal] =
		ops.start.ackReceiver orElse {
			implicit ctx: SignallingContext[Equipment.MockSourceSignal] => {
				case SenderProcessType (msg, load) =>
				log.info (s"Got Domain Message in Sender $msg")
				testActor.ref ! s"FromSender: $msg"
				ops.start.send (load)
				log.info (s"Sent $load through channel ${ops.start.channelName}")
				senderRunner
				case other =>
				fail (s"Unexpected Message $other");
				senderRunner
			}
		}



	val sender = new AgentTemplate.Wrapper("sender", clock, testController.ref, senderConfigurer)


	def receiverRunner(implicit ops: Channel.Ops[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal], ctx: SignallingContext[Equipment.MockSinkSignal]): DomainRun[Equipment.MockSinkSignal] =
		ops.end.loadReceiver orElse DomainRun {
			case other =>
				log.info(s"Received Other Message at Receiver: $other")
				fail(s"Unexpected Message $other")
				receiverRunner
		}


	def receiverConfigurer(implicit ops: Channel.Ops[ProbeLoad, Equipment.MockSourceSignal, Equipment.MockSinkSignal]): DomainConfigure[Equipment.MockSinkSignal] =
		new DomainConfigure[Equipment.MockSinkSignal] {
			override def configure(config: Equipment.MockSinkSignal)(implicit ctx: SignallingContext[Equipment.MockSinkSignal]): DomainRun[Equipment.MockSinkSignal] = config match {
				case ReceiverConfigType(msg) =>
					ops.registerEnd(sink(ctx.aCtx.self))
					testActor.ref ! s"From Receiver-Cfg: $msg"
					log.info(s"Configured Receiver and installing its Runner")
					receiverRunner
			}
		}

	val receiver = new AgentTemplate.Wrapper("receiver", clock, testController.ref, receiverConfigurer)

	val mockProcessorOrigin = testKit.createTestProbe[SimSignal]


	"A Channel" when {
		"A. Initialized with a capacity of 2 and no Lookup" should {
			val s = testKit.spawn(sender.init, "Sender")
			val r = testKit.spawn(receiver.init, "Receiver")
			"A1. Allow for Start and End registration" in {
				startTime()
				enqueueConfigure(s, mockProcessorOrigin.ref, 0L, SenderConfigType("ConfigureSender"))
				testActor.expectMessage("ConfigureSender")
				enqueueConfigure(r, mockProcessorOrigin.ref, 0L, ReceiverConfigType("ConfigureReceiver"))
				testActor.expectMessage("From Receiver-Cfg: ConfigureReceiver")
				testActor.ref ! "DoneConfiguring"
			}
			"A2: Transfer a load between the sender and receiver with a Delay" in {
				testActor.expectMessage("DoneConfiguring")
				val probe1 = ProbeLoad("probe1")
				val msg1 = Run(mockProcessorOrigin.ref, 5L, SenderProcessType("probe1", probe1))
				enqueue(s, mockProcessorOrigin.ref, 5L, SenderProcessType("probe1", probe1))

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
