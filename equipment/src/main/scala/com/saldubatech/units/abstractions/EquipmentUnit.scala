package com.saldubatech.units.abstractions

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}

import scala.collection.mutable
import scala.reflect.ClassTag

object EquipmentUnit {
	abstract class InductSink[SinkSignal >: ChannelConnections.ChannelDestinationMessage](chOps: Channel.Ops[MaterialLoad, _, SinkSignal], override val ref: Processor.Ref)
		extends Channel.Sink[MaterialLoad, SinkSignal] {
		lazy val end = chOps.registerEnd(this)
	}

	abstract class DischargeSource[SourceSignal >: ChannelConnections.ChannelSourceMessage](chOps: Channel.Ops[MaterialLoad, SourceSignal, _], override val ref: Processor.Ref) extends Channel.Source[MaterialLoad, SourceSignal] {
		lazy val start = chOps.registerStart(this)
	}




		def nopRunner[Signal]: Processor.DomainRun[Signal] = (ctx: Processor.SignallingContext[Signal]) => {
			case n: Any if false => Processor.DomainRun.same
		}
}

trait EquipmentUnit[EQ_SIGNAL >: ChannelConnections.ChannelSourceSink] {

	lazy val self: Processor.Ref = _self
	private var _self: Processor.Ref = null
	def installSelf(s: Processor.Ref) = _self = s
	val name: String
	private var _manager: Processor.Ref = _
	protected lazy val manager: Processor.Ref = _manager
	def installManager(m: Processor.Ref) = _manager = m

	type HOST <: CarriageUnit[EQ_SIGNAL]
	type EXTERNAL_COMMAND <: EQ_SIGNAL
	type NOTIFICATION <: EquipmentManager.Notification

	type CTX = Processor.SignallingContext[EQ_SIGNAL]
	type RUNNER = Processor.DomainRun[EQ_SIGNAL]

}
