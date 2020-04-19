package com.saldubatech.units

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.util.LogEnabled
import org.scalatest.WordSpec

import scala.collection.mutable

object UnitsFixture {

	trait DownstreamSignal extends ChannelConnections.DummySinkMessageType
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends ChannelConnections.DummySourceMessageType
	case object UpstreamConfigure extends UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends UpstreamSignal

	case object ConsumeLoad extends Identification.Impl() with ChannelConnections.DummySinkMessageType

	trait Fixture[DomainMessage] extends LogEnabled {
		var _ref: Option[Processor.Ref] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture[DestinationSignal >: ChannelConnections.ChannelDestinationMessage](ops: Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, DestinationSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySourceMessageType] {
		private val pending: mutable.Queue[MaterialLoad] = mutable.Queue.empty

		lazy val source = new Channel.Source[MaterialLoad, ChannelConnections.DummySourceMessageType] {
			override lazy val ref: Processor.Ref = _ref.head

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, ChannelConnections.DummySourceMessageType], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType]): Processor.DomainRun[ChannelConnections.DummySourceMessageType] = {
				//log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				pending.headOption.find(ops.start.send(_)).foreach(_ => pending.dequeue)
				testMonitor ! s"Received Load Acknowledgement through Channel: ${chStart.channelName} with $load at ${ctx.now}"
				runner
			}
		}
		ops.registerStart(source)

		val runner: Processor.DomainRun[ChannelConnections.DummySourceMessageType] =
			ops.start.ackReceiver orElse {
				implicit ctx: Processor.SignallingContext[ChannelConnections.DummySourceMessageType] => {
					case TestProbeMessage(msg, load) =>
						//log.info(s"Got Domain Message in Sender $msg")
						testMonitor ! s"FromSender: $msg"
						if(!ops.start.send(load)) {
							pending += load
//							log.debug(s"Queued $load for channel ${ops.start.channelName}")
						} else {
//							log.debug(s"Sent $load through channel ${ops.start.channelName}")
						}
						runner
					case other =>
						//log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				}
			}
	}

	class SinkFixture[SourceSignal >: ChannelConnections.ChannelSourceMessage](ops: Channel.Ops[MaterialLoad, SourceSignal, ChannelConnections.DummySinkMessageType])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[ChannelConnections.DummySinkMessageType] {
		val sink = new Channel.Sink[MaterialLoad, ChannelConnections.DummySinkMessageType] {
			override lazy val ref: Processor.Ref = _ref.head

			override def loadArrived(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				testMonitor ! s"Load $load arrived to Sink via channel ${endpoint.channelName} at ${ctx.now}"
				endpoint.getNext
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[ChannelConnections.DummySinkMessageType]): Processor.DomainRun[ChannelConnections.DummySinkMessageType] = {
				//log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
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
						//log.info(s"Received Other Message at Receiver: $other")
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
