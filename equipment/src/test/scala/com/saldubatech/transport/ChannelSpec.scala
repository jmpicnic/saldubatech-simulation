/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{Delay, Enqueue, StartTime}
import com.saldubatech.ddes.Processor.CommandContext
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.test.BaseSpec

import scala.concurrent.duration._

object ChannelSpec {
	case class ProbeLoad(lid: String) extends Identification.Impl(lid)


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
	val underTest = new Channel(() => Some(7), Set("card1", "card2"), "underTest")

	sealed trait SenderType
	case class SenderConfigType(id: String) extends SenderType
	case class SenderProcessType(id: String, l: ProbeLoad) extends SenderType
	case class AcknowledgeLoad1(override val channel: String, override val load: ProbeLoad, override val resource: String) extends SenderType with Channel.AcknowledgeLoad[ProbeLoad]

	type SenderSignal = SenderType

	sealed trait ReceiverType
	case class ReceiverConfigType(id: String) extends ReceiverType
	case class ReceiverProcessType(id: String, load: ProbeLoad) extends ReceiverType
	case class TransferLoad1(override val channel: String, override val load: ProbeLoad, override val resource: String) extends ReceiverType with Channel.TransferLoad[ProbeLoad]
	case class LoadConsumed1(override val channel: String) extends ReceiverType with Channel.LoadConsumed[ProbeLoad]

	type ReceiverSignal = ReceiverType

	implicit object source extends Channel.Source[ProbeLoad, SenderSignal]{
		override def acknowledgeBuilder(ch: String, ld: ProbeLoad, rs: String): Signal = new Channel.AcknowledgeLoad[ProbeLoad] with SenderSignal {
			override val channel: String = ch
			override val load: ProbeLoad = ld
			override val resource: String = rs
			override def toString = s"Sender.AcknowledgeLoad(ch: $channel, ld: $load, rs: $resource)"
		}
		override def loadAcknowledge(load: ProbeLoad): Option[ProbeLoad] = {testActor.ref ! s"${load.lid}-Acknowledged";Some(load)}
	}

	implicit object sink extends Channel.Sink[ProbeLoad, ReceiverSignal] {
		override def transferBuilder(ch: String, ld: ProbeLoad, rs: String): TransferSignal = new Channel.TransferLoad[ProbeLoad] with ReceiverSignal {
			override val channel: String = ch
			override val load: ProbeLoad = ld
			override val resource: String = rs
			override def toString = s"Receiver.TransferLoad(ch: $channel, ld: $load, rs: $resource)"
		}

		override def releaseBuilder(ch: String): ConsumeSignal = new Channel.LoadConsumed[ProbeLoad] with ReceiverSignal {
			override val channel: String = ch
			override def toString = s"Receiver.ReleaseLoad(ch: $channel)"
		}

		override def loadArrived(endpoint: Channel.End[ProbeLoad, ReceiverSignal], load: ProbeLoad)(implicit ctx: CommandContext[ReceiverSignal]): Boolean = {testActor.ref ! s"${load.lid}-Received";true}
	}

	implicit object senderChannelOps extends Channel.Ops[ProbeLoad, SenderSignal, ReceiverSignal](underTest)

	def senderConfigurer(implicit ops: Channel.Ops[ProbeLoad, SenderSignal, ReceiverSignal]) =
		new Processor.DomainConfigure[SenderSignal] {
			override def configure(config: SenderSignal)(implicit ctx: Processor.CommandContext[SenderSignal]): Processor.DomainRun[SenderSignal] = config match {
				case SenderConfigType(msg) =>
					ops.start.register(ctx.aCtx.self);	testActor.ref ! msg
					senderRunner
			}
		}
	def senderRunner(implicit ops: Channel.Ops[ProbeLoad, SenderSignal, ReceiverSignal]): Processor.DomainRun[SenderSignal] =
		new Processor.DomainRun[SenderSignal]{
			override def process(processMessage: SenderSignal)(implicit ctx: Processor.CommandContext[SenderSignal]): Processor.DomainRun[SenderSignal] = {
				val resultLoad = (ops.start.receiveAcknowledgement orElse[SenderType, Option[ProbeLoad]] {
					case SenderProcessType(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testActor.ref ! msg
						Some(load).filter(ops.start.send)
					case other =>
						fail(s"Unexpected Message $other");None
				})(processMessage)
				this
			}
		}


	val sender = new Processor("sender", globalClock, testController.ref, senderConfigurer)

	def receiverRunner(implicit ops: Channel.Ops[ProbeLoad, SenderSignal, ReceiverSignal]): Processor.DomainRun[ReceiverSignal] =
		new Processor.DomainRun[ReceiverSignal]{
			override def process(processMessage: ReceiverSignal)(implicit ctx: Processor.CommandContext[ReceiverSignal]): Processor.DomainRun[ReceiverSignal] = {
				log.info(s"Received Message at Receiver: $processMessage")
				processMessage match {
					case ReceiverProcessType(msg, load) =>
						log.info(s"Received a domain message: $msg")
						ops.end.releaseLoad(load)
						testActor.ref ! msg
						Some(load)
					case other =>
						if(ops.end.receiveLoad(other) isEmpty) fail(s"Unexpected Message $other");None
				}
				this
			}
		}

	def receiverConfigurer(implicit ops: Channel.Ops[ProbeLoad, SenderSignal, ReceiverSignal]): Processor.DomainConfigure[ReceiverSignal] =
		new Processor.DomainConfigure[ReceiverSignal] {
			override def configure(config: ReceiverSignal)(implicit ctx: Processor.CommandContext[ReceiverSignal]): Processor.DomainRun[ReceiverSignal] = config match {
				case ReceiverConfigType(msg) =>
					ops.end.register(ctx.aCtx.self)
					testActor.ref ! msg
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
				testActor.expectMessage("ConfigureReceiver")
			}
			"A2: Transfer a load between the sender and receiver with a Delay" in {
				val probe1 = ProbeLoad("probe1")
				val msg1 = Processor.ProcessCommand(mockProcessorOrigin.ref, 5L, SenderProcessType("probe1", probe1))
				globalClock ! Enqueue(s, 5, msg1)
				globalClock ! Enqueue(r, 15, Processor.ProcessCommand(mockProcessorOrigin.ref, 15, ReceiverProcessType("probe1-release", probe1)))

				globalClock ! StartTime(0L)
				testActor.expectMessage("probe1")
				testActor.expectMessage("probe1-Received")
				senderChannelOps.start.availableSlots shouldBe 1
				testActor.expectMessage("probe1-release")
				testActor.expectMessage("probe1-Acknowledged")
				testActor.expectNoMessage(500 millis)
				senderChannelOps.start.availableSlots shouldBe 2
			}
		}

	}

}
