/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, FishingOutcomes}
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.{CompleteAction, Enqueue, StartActionOnReceive, StartTime}
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.SimulationController.ControllerMessage
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


	val sendingConfiguration =
		Channel.SendingConfiguration[ProbeLoad](() => Some(5), (ch, ld, rs) => AcknowledgeLoad1(ch, ld, rs))
	val receivingConfiguration =
		Channel.ReceivingConfiguration[ProbeLoad](None, (ch: String, ld: ProbeLoad, rs: String) => TransferLoad1(ch, ld, rs), rs => DequeueNextLoad1(rs))
	val underTest = new Channel[ProbeLoad, SenderSignal, ReceiverSignal](Set("card1", "card2"), "underTest", sendingConfiguration, receivingConfiguration)



	sealed trait SenderType
	case class SenderConfigType(id: String) extends SenderType
	case class SenderProcessType(id: String, l: ProbeLoad) extends SenderType
	case class AcknowledgeLoad1(override val channel: String, override val load: ChannelSpec.ProbeLoad, override val resource: String) extends SenderType with Channel.AcknowledgeLoad[ProbeLoad]

	type SenderSignal = SenderType

	sealed trait ReceiverType
	case class ReceiverConfigType(id: String) extends ReceiverType
	case class ReceiverProcessType(id: String, load: ProbeLoad) extends ReceiverType
	case class TransferLoad1(override val channel: String, override val load: ChannelSpec.ProbeLoad, override val resource: String) extends ReceiverType with Channel.TransferLoad[ProbeLoad]
	case class DequeueNextLoad1(override val channel: String) extends ReceiverType with Channel.DequeueNextLoad[ProbeLoad]

	type ReceiverSignal = ReceiverType


	def senderConfigurer(start: underTest.Start) = (p: Processor.Configuring[SenderSignal]) =>
		new Processor.DomainConfigure[SenderSignal] {
			override def configure(config: SenderSignal)(implicit ctx: Processor.CommandContext[SenderSignal]): Unit = config match {
				case SenderConfigType(msg)=>
					start.register(ctx.host, l => testActor.ref ! s"${l.lid}-Acknowledged")
					testActor.ref ! msg
			}
		}

	def senderRunner(start: underTest.Start): Processor.Running[SenderSignal] => Processor.DomainRun[SenderSignal] = (p: Processor.Running[SenderSignal]) =>
		new Processor.DomainRun[SenderSignal]{
			override def process(processMessage: SenderSignal)(implicit ctx: Processor.CommandContext[SenderSignal]): Unit = {
				(start.receiveAcknowledgement orElse[SenderType, Option[ProbeLoad]] {
					case SenderProcessType(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testActor.ref ! msg
						Some(load).filter(start.send)
				})(processMessage)
			}
		}

	def receiverConfigurer(end: underTest.End) = (p: Processor.Configuring[ReceiverSignal]) =>
		new Processor.DomainConfigure[ReceiverSignal] {
			override def configure(config: ReceiverSignal)(implicit ctx: Processor.CommandContext[ReceiverSignal]): Unit = config match {
				case ReceiverConfigType(msg) =>
					end.register(ctx.host, l => testActor.ref ! s"${l.lid}-Received" )
					testActor.ref ! msg
			}
		}
	def receiverRunner(end: underTest.End): Processor.Running[ReceiverSignal] => Processor.DomainRun[ReceiverSignal] = (p: Processor.Running[ReceiverSignal]) =>
		new Processor.DomainRun[ReceiverSignal]{
			override def process(processMessage: ReceiverSignal)(implicit ctx: Processor.CommandContext[ReceiverSignal]): Unit = {
				log.info(s"Received Message at Receiver: $processMessage")
				(end.receiveLoad orElse[ReceiverType, Option[ProbeLoad]] {
					case ReceiverProcessType(msg, load) =>
						log.info(s"Received a domain message: $msg")
						end.releaseLoad(load)
						testActor.ref ! msg
						Some(load)
				})(processMessage)
			}
		}
	val mockProcessorOrigin = testKit.createTestProbe[Processor.ProcessorMessage]


	"A Channel" when {
		"A. Initialized with a capacity of 2 and no Lookup" should {
			val sender = new Processor("sender", globalClock, testController.ref, senderRunner(underTest.start), senderConfigurer(underTest.start))
			val receiver = new Processor("receiver", globalClock, testController.ref, receiverRunner(underTest.end), receiverConfigurer(underTest.end))
			val s = testKit.spawn(sender.init, "Sender")
			val r = testKit.spawn(receiver.init, "Receiver")
			"A1. Allow for Start and End registration" in {
				s ! Processor.ConfigurationCommand[SenderConfigType](mockProcessorOrigin.ref, 0L, SenderConfigType("ConfigureSender"))
				testActor.expectMessage("ConfigureSender")
				r ! Processor.ConfigurationCommand[ReceiverConfigType](mockProcessorOrigin.ref, 0L, ReceiverConfigType("ConfigureReceiver"))
				testActor.expectMessage("ConfigureReceiver")
			}
			"A2: Transfer a load betweek the sender and receiver with a Delay" in {
				val probe1 = ProbeLoad("probe1")
				val msg1 = Processor.ProcessCommand(mockProcessorOrigin.ref, 5L, SenderProcessType("probe1", probe1))
				globalClock ! Enqueue(s, 5, msg1)
				globalClock ! Enqueue(r, 15, Processor.ProcessCommand(mockProcessorOrigin.ref, 15, ReceiverProcessType("probe1-release", probe1)))

				globalClock ! StartTime(0L)
				testActor.expectMessage("probe1")
				testActor.expectMessage("probe1-Received")
				underTest.start.availableSlots shouldBe 1
				testActor.expectMessage("probe1-release")
				testActor.expectMessage("probe1-Acknowledged")
				testActor.expectNoMessage(500 millis)
				underTest.start.availableSlots shouldBe 2
			}
		}

	}

}
