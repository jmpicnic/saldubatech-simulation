package com.saldubatech.units.`abstract`

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Processor
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}

import scala.reflect.ClassTag

object EquipmentUnit {
	class Definitions[SinkSignal >: ChannelConnections.ChannelDestinationMessage, SourceSignal >: ChannelConnections.ChannelSourceMessage, Signal] {


		type CTX = Processor.SignallingContext[Signal]
		type RUNNER = Processor.DomainRun[Signal]

		abstract class InductSink(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, _, SinkSignal], override val ref: Processor.Ref)
			extends Channel.Sink[MaterialLoad, SinkSignal] {

			lazy val end = chOps.registerEnd(this)
		}

		abstract class DischargeSource(manager: Processor.Ref, chOps: Channel.Ops[MaterialLoad, SourceSignal, _], override val ref: Processor.Ref) extends Channel.Source[MaterialLoad, SourceSignal] {
			lazy val start = chOps.registerStart(this)
		}

		val nopRunner: Processor.DomainRun[Signal] = (ctx: CTX) => {
			case n: Any if false => Processor.DomainRun.same
		}
	}

}
