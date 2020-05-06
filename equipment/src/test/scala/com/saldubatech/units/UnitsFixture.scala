package com.saldubatech.units

import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.protocols.Equipment
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.util.LogEnabled
import org.scalatest.WordSpec

import scala.collection.mutable

object UnitsFixture {

	trait DownstreamSignal extends Equipment.MockSinkSignal
	case object DownstreamConfigure extends Identification.Impl() with DownstreamSignal

	trait UpstreamSignal extends Equipment.MockSourceSignal
	case object UpstreamConfigure extends Identification.Impl() with  UpstreamSignal
	case class TestProbeMessage(msg: String, load: MaterialLoad) extends Identification.Impl() with UpstreamSignal

	case object ConsumeLoad extends Identification.Impl() with Equipment.MockSinkSignal

	trait Fixture[DomainMessage <: DomainSignal] extends LogEnabled {
		var _ref: Option[SimRef] = None
		val runner: Processor.DomainRun[DomainMessage]
	}
	class SourceFixture[DestinationSignal >: Equipment.ChannelSinkSignal <: DomainSignal](ops: Channel.Ops[MaterialLoad, Equipment.MockSourceSignal, DestinationSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSourceSignal] {
		private val pending: mutable.Queue[MaterialLoad] = mutable.Queue.empty

		lazy val source = new Channel.Source[MaterialLoad, Equipment.MockSourceSignal] {
			override lazy val ref: SimRef = _ref.head

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, Equipment.MockSourceSignal], load: MaterialLoad)(implicit ctx: Processor.SignallingContext[Equipment.MockSourceSignal]): Processor.DomainRun[Equipment.MockSourceSignal] = {
				//log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				pending.headOption.find(ops.start.send(_)).foreach(_ => pending.dequeue)
				testMonitor ! s"Received Load Acknowledgement through Channel: ${chStart.channelName} with $load at ${ctx.now}"
				runner
			}
		}
		ops.registerStart(source)

		val runner: Processor.DomainRun[Equipment.MockSourceSignal] =
			ops.start.ackReceiver orElse {
				implicit ctx: Processor.SignallingContext[Equipment.MockSourceSignal] => {
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

	class SinkFixture[SourceSignal >: Equipment.ChannelSourceSignal <: DomainSignal](ops: Channel.Ops[MaterialLoad, SourceSignal, Equipment.MockSinkSignal])(testMonitor: ActorRef[String], hostTest: WordSpec) extends Fixture[Equipment.MockSinkSignal] {
		val sink = new Channel.Sink[MaterialLoad, Equipment.MockSinkSignal] {
			override lazy val ref: SimRef = _ref.head

			override def loadArrived(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				testMonitor ! s"Load $load arrived to Sink via channel ${endpoint.channelName} at ${ctx.now}"
				endpoint.getNext
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, Equipment.MockSinkSignal], load: MaterialLoad, at: Option[Int])(implicit ctx: Processor.SignallingContext[Equipment.MockSinkSignal]): Processor.DomainRun[Equipment.MockSinkSignal] = {
				//log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		val channelEnd = ops.registerEnd(sink)

		val runner: Processor.DomainRun[Equipment.MockSinkSignal] =
			ops.end.loadReceiver orElse {
				ctx: Processor.SignallingContext[Equipment.MockSinkSignal] => {
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
	
	def configurer[DomainMessage <: DomainSignal](fixture: Fixture[DomainMessage])(monitor: ActorRef[String]) =
		new Processor.DomainConfigure[DomainMessage] {
			override def configure(config: DomainMessage)(implicit ctx: Processor.SignallingContext[DomainMessage]): Processor.DomainRun[DomainMessage] = {
				monitor ! s"Received Configuration: $config"
				fixture._ref = Some(ctx.aCtx.self)
				fixture.runner
			}
		}
}
