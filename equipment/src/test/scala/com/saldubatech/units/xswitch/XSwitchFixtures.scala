package com.saldubatech.units.xswitch

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.protocols.Equipment
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.lift
import com.saldubatech.units.lift.{LoadAwareXSwitch, XSwitch}
import com.saldubatech.util.LogEnabled
import org.scalatest.WordSpec

import scala.reflect.ClassTag

object XSwitchFixtures {

	object XSwitchHelpers {
		implicit val tsBuilderXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
		implicit val psBuilderXS = (load: MaterialLoad, resource: String, idx: Int, channel: String) => new Channel.PulledLoadImpl[MaterialLoad](load, resource, idx, channel) with Equipment.XSwitchSignal
		implicit val ackBuilderXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
	}

	object LoadAwareHelpers {
		implicit val tsBuilderLAXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
		implicit val psBuilderLAXS = (load: MaterialLoad, resource: String, idx: Int, channel: String) => new Channel.PulledLoadImpl[MaterialLoad](load, resource, idx, channel) with Equipment.XSwitchSignal
		implicit val ackBuilderLAXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
	}

	trait DownstreamSignal extends Equipment.MockSinkSignal
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends Equipment.MockSourceSignal
	case object UpstreamConfigure extends Identification.Impl() with UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends Identification.Impl() with UpstreamSignal

	case object ConsumeLoad extends Identification.Impl() with Equipment.MockSinkSignal

	class InboundChannelImpl[SIGNAL >: Equipment.ChannelSignal]
	(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
	(implicit tsBuilder: (String, MaterialLoad, String) => Channel.TransferLoad[MaterialLoad] with SIGNAL, psBuilder: (MaterialLoad, String, Int, String) => Channel.PulledLoad[MaterialLoad] with SIGNAL)
		extends Channel[MaterialLoad, Equipment.MockSourceSignal, SIGNAL](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with SIGNAL
		type PullSignal = Channel.PulledLoad[MaterialLoad] with SIGNAL
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.MockSourceSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = tsBuilder(channel, load, resource)

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal =  psBuilder(ld, card, idx, this.name)

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.UnitSorterSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.UnitSorterSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSourceSignal
	}

	class OutboundChannelImpl[SIGNAL >: Equipment.ChannelSignal]
	(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
	(implicit ackBuilder: (String, MaterialLoad, String) => Channel.AcknowledgeLoad[MaterialLoad] with SIGNAL)
		extends Channel[MaterialLoad, SIGNAL, Equipment.MockSinkSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.MockSinkSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.MockSinkSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with SIGNAL
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSinkSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.MockSinkSignal

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.MockSinkSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.MockSinkSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = ackBuilder(channel, load, resource)
	}

	trait Fixture[DomainMessage <: DomainSignal] extends LogEnabled {
		var _ref: Option[SimRef] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture[SIGNAL >: Equipment.ChannelSinkSignal <: DomainSignal](ops: Channel.Ops[MaterialLoad, Equipment.MockSourceSignal, SIGNAL])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSourceSignal] {

		lazy val source = new Channel.Source[MaterialLoad, Equipment.MockSourceSignal] {
			override lazy val ref: SimRef = _ref.head

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
						ops.start.send(load)
						log.info(s"Sent $load through channel ${ops.start.channelName}")
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				}
			}
	}

	class SinkFixture[SIGNAL >: Equipment.ChannelSourceSignal <: DomainSignal](ops: Channel.Ops[MaterialLoad, SIGNAL, Equipment.MockSinkSignal], controlled: Boolean)(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSinkSignal] {
		val sink = new Channel.Sink[MaterialLoad, Equipment.MockSinkSignal] {
			override lazy val ref: SimRef = _ref.head

			override def loadArrived(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				testMonitor ! s"Load $load arrived to Sink via channel ${endpoint.channelName}"
				if(!controlled) endpoint.getNext
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		val channelEnd = ops.registerEnd(sink)

		val runner: Processor.DomainRun[Equipment.MockSinkSignal] =
			ops.end.loadReceiver orElse {
				implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal] => {
					case ConsumeLoad =>
						if(controlled) testMonitor ! s"Got load ${channelEnd.getNext}"
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"SinkFixture: ${ops.ch.name}: Unexpected Message $other")
				}
			}
	}

	def configurer[DomainMessage <: DomainSignal](fixture: Fixture[DomainMessage])(monitor: ActorRef[String]) =
		new Processor.DomainConfigure[DomainMessage] {
			override def configure(config: DomainMessage)(implicit ctx: Processor.SignallingContext[DomainMessage]): Processor.DomainRun[DomainMessage] = {
				monitor ! s"Received Configuration: $config"
				fixture._ref = Some(ctx.aCtx.self)
				fixture.runner
			}
		}
}
