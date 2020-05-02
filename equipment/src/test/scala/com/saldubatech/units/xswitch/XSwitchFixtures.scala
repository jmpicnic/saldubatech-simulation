package com.saldubatech.units.xswitch

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.lift
import com.saldubatech.units.lift.{LoadAwareXSwitch, XSwitch}
import com.saldubatech.units.unitsorter.UnitSorterSignal
import com.saldubatech.util.LogEnabled
import org.scalatest.WordSpec

import scala.reflect.ClassTag

object XSwitchFixtures {

	object XSwitchHelpers {
		implicit val tsBuilderXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal
		implicit val psBuilderXS = (load: MaterialLoad, resource: String, idx: Int, channel: String) => new Channel.PulledLoadImpl[MaterialLoad](load, resource, idx, channel) with XSwitch.XSwitchSignal
		implicit val ackBuilderXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal
	}

	object LoadAwareHelpers {
		implicit val tsBuilderLAXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareXSwitch.XSwitchSignal
		implicit val psBuilderLAXS = (load: MaterialLoad, resource: String, idx: Int, channel: String) => new Channel.PulledLoadImpl[MaterialLoad](load, resource, idx, channel) with lift.LoadAwareXSwitch.XSwitchSignal
		implicit val ackBuilderLAXS = (channel: String, load: MaterialLoad, resource: String) => new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with lift.LoadAwareXSwitch.XSwitchSignal
	}

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	case object ConsumeLoad extends Identification.Impl() with ChannelConnections.DummySinkMessageType

	class InboundChannelImpl[SIGNAL >: ChannelConnections.ChannelSourceSink]
	(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
	(implicit tsBuilder: (String, MaterialLoad, String) => Channel.TransferLoad[MaterialLoad] with SIGNAL, psBuilder: (MaterialLoad, String, Int, String) => Channel.PulledLoad[MaterialLoad] with SIGNAL)
		extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, SIGNAL](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with SIGNAL
		type PullSignal = Channel.PulledLoad[MaterialLoad] with SIGNAL
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = tsBuilder(channel, load, resource)

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal =  psBuilder(ld, card, idx, this.name)

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
	}

	class OutboundChannelImpl[SIGNAL >: ChannelConnections.ChannelSourceSink]
	(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
	(implicit ackBuilder: (String, MaterialLoad, String) => Channel.AcknowledgeLoad[MaterialLoad] with SIGNAL)
		extends Channel[MaterialLoad, SIGNAL, ChannelConnections.DummySinkMessageType](delay, deliveryTime, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with SIGNAL
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with ChannelConnections.DummySinkMessageType

		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with ChannelConnections.DummySinkMessageType
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with ChannelConnections.DummySinkMessageType

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = ackBuilder(channel, load, resource)
	}

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Processor.Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture[SIGNAL >: ChannelConnections.ChannelDestinationMessage](ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, SIGNAL])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySourceMessageType] {

		lazy val source = new Channel.Source[MaterialLoad, ChannelConnections.DummySourceMessageType] {
			override lazy val ref: Processor.Ref = _ref.head

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

	class SinkFixture[SIGNAL >: ChannelConnections.ChannelSourceMessage](ops: Channel.Ops[MaterialLoad, SIGNAL, ChannelConnections.DummySinkMessageType], controlled: Boolean)(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySinkMessageType] {
		val sink = new Channel.Sink[MaterialLoad, ChannelConnections.DummySinkMessageType] {
			override lazy val ref: Processor.Ref = _ref.head

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load arrived to Sink via channel ${endpoint.channelName}"
				if(!controlled) endpoint.getNext
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
				implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType] => {
					case ConsumeLoad =>
						if(controlled) testMonitor ! s"Got load ${channelEnd.getNext}"
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
