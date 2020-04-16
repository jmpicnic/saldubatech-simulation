/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.base.{AssetBox, Identification}
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.{DomainRun, SignallingContext}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object Channel {

	trait AcknowledgeLoad[L <: Identification] extends ChannelConnections.ChannelSourceMessage {
		val channel: String;
		val load: L;
		val resource: String
	}
	abstract class AckLoadImpl[L <: Identification](override val channel: String, override val load: L, override val resource: String)
		extends Identification.Impl() with AcknowledgeLoad[L] {
		override def toString = s"AcknowledgeLoad(load: $load, channel: $channel, resource: $resource)"
	}



	trait TransferLoad[L <: Identification] extends ChannelConnections.ChannelDestinationMessage {
		val channel: String;
		val load: L;
		val resource: String
	}
	abstract class TransferLoadImpl[L <: Identification](override val channel: String, override val load: L, override val resource: String)
		extends Identification.Impl() with TransferLoad[L]{
		override def toString = s"TransferLoad(load: $load, channel: $channel, resource: $resource)"
	}


	trait Endpoint {
		val channelName: String
	}
	trait Start[LOAD <: Identification, SourceProfile >: ChannelConnections.ChannelSourceMessage] extends Endpoint {
		val source: Source[LOAD, SourceProfile]

		def availableCards: Int
		def reserveCard: Option[String]
		def send(load: LOAD)(implicit ctx: SignallingContext[SourceProfile]): Boolean
		def send(load: LOAD, withCard: String)(implicit ctx: SignallingContext[SourceProfile]): Boolean
		def ackReceiver: Processor.DomainRun[SourceProfile]
	}

	trait PulledLoad[L <: Identification] extends ChannelConnections.ChannelDestinationMessage {
		val load: L
		val channel: String
		val idx: Int
	}
	abstract class PulledLoadImpl[L <: Identification](override val load: L, override val idx: Int, override val channel: String)
		extends Identification.Impl() with PulledLoad[L]{
		override def toString = s"PulledLoad(load: $load, channel: $channel, idx: $idx)"
	}


	trait End[LOAD <: Identification, SinkProfile >: ChannelConnections.ChannelDestinationMessage] extends Endpoint {
		val sink: Sink[LOAD, SinkProfile]
		val receivingSlots: Int
		def doEndpointReceiving(load: LOAD, resource: String)(implicit ctx: SignallingContext[SinkProfile]): Option[Int]
		def getNext(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)]
		def get(l: LOAD)(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)]
		def get(idx: Int)(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)]
		def peekNext: Option[(LOAD, String)]
		def peek(l: LOAD): Option[(LOAD, String)]
		def peek(idx: Int): Option[(LOAD, String)]
		def loadReceiver: Processor.DomainRun[SinkProfile]
	}

	trait Sink[L <: Identification, SinkProfile >: ChannelConnections.ChannelDestinationMessage] {
		val ref: Processor.Ref
		def loadArrived(endpoint: End[L, SinkProfile], load: L, at: Option[Int] = None)(implicit ctx: SignallingContext[SinkProfile]): Processor.DomainRun[SinkProfile]
		def loadReleased(endpoint: End[L, SinkProfile], load: L, at: Option[Int] = None)(implicit ctx: SignallingContext[SinkProfile]): Processor.DomainRun[SinkProfile]
	}

	trait Source[L <: Identification, SourceProfile >: ChannelConnections.ChannelSourceMessage] {
		val ref: Processor.Ref
		def loadAcknowledged(ep: Channel.Start[L, SourceProfile], load: L)(implicit ctx: SignallingContext[SourceProfile]): Processor.DomainRun[SourceProfile]
	}

	object Ops {
		def apply[LOAD <: Identification, SourceProfile >: ChannelConnections.ChannelSourceMessage,
			SinkProfile >: ChannelConnections.ChannelDestinationMessage](ch: Channel[LOAD, SourceProfile, SinkProfile]) = new Ops(ch)
	}
	class Ops[LOAD <: Identification, SourceProfile >: ChannelConnections.ChannelSourceMessage,
		SinkProfile >: ChannelConnections.ChannelDestinationMessage](val ch: Channel[LOAD, SourceProfile, SinkProfile])
		extends LogEnabled {

		private var _start: Option[Start[LOAD, SourceProfile]] = None
		lazy val start = _start.head
		def registerStart(source: Channel.Source[LOAD, SourceProfile]): Start[LOAD, SourceProfile] = {
			val r = buildStart(source)
			_start = Some(r)
			log.debug(s"Registering Source: $source for channel ${r.channelName}")
			r
		}
		private def buildStart(sourcePar: Channel.Source[LOAD, SourceProfile]): Start[LOAD, SourceProfile] = new Start[LOAD, SourceProfile] {
			override lazy val source = sourcePar
			override lazy val channelName = ch.name
			private val localBox = AssetBox(ch.cards, ch.name + "Start")
			private val reserved: mutable.Set[String] = mutable.Set.empty

			override def reserveCard: Option[String] = localBox.checkoutAny.map { rs => reserved += rs; rs }

			override def send(load: LOAD, withCard: String)(implicit ctx: SignallingContext[SourceProfile]): Boolean =
				reserved.find(_ == withCard).map(c => doSend(load, c)).isDefined
			override def send(load: LOAD)(implicit ctx: SignallingContext[SourceProfile]): Boolean =
				localBox.checkoutAny.map(c => doSend(load, c)).isDefined

			private def doSend(load: LOAD, withCard: String)(implicit ctx: SignallingContext[SourceProfile]) = {
				if(_end isEmpty) throw new IllegalStateException(s"Cannot send through a channel without its endpoint configured ${ch.name}")
				(
					for {
						doTell <- _end.map(e => ctx.signaller(e.sink.ref))
					} yield {
						log.debug(s"Sending Load: $load from ${start.source.ref} on channel ${ch.name}")
						reserved.remove(withCard)
						doTell(ch.transferBuilder(ch.name, load, withCard), ch.delay())
					}).isDefined
			}
			override def availableCards: Int = localBox.available

			override def ackReceiver: Processor.DomainRun[SourceProfile] = {
				implicit ctx: SignallingContext[SourceProfile] => {
					case ackMsg: AcknowledgeLoad[LOAD] if ackMsg.channel == ch.name =>
						log.debug(s"Processing Load Acknowledgement for ${ackMsg.load}")
						localBox.checkin(ackMsg.resource)
						source.loadAcknowledged(this, ackMsg.load)
				}
			}
		}

		lazy val end = _end.head
		private var _end: Option[End[LOAD, SinkProfile]] = None
		def registerEnd(sink: Sink[LOAD, SinkProfile]): End[LOAD, SinkProfile] = {
			_end = Some(buildEnd(sink))
			log.debug(s"Registering Sink: $sink for channel ${_end.head.channelName}")
			_end.head
		}
		private def buildEnd(sinkParam: Sink[LOAD, SinkProfile]): End[LOAD, SinkProfile] = {
			new End[LOAD, SinkProfile] {
				override lazy val sink = sinkParam
				override lazy val channelName = ch.name
				override lazy val receivingSlots = ch.configuredOpenSlots
				private lazy val openSlots: mutable.Set[Int] = mutable.Set((0 until receivingSlots): _*)
				private val delivered: mutable.SortedMap[Int, (LOAD, String)] = mutable.SortedMap.empty
				private val pending: mutable.Queue[(LOAD, String)] = mutable.Queue.empty

				override def getNext(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)] = delivered.headOption.flatMap { e => get(e._1) }
				override def get(l: LOAD)(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)] = delivered.find(e => e._2._1 == l).flatMap(e => get(e._1))
				override def get(idx: Int)(implicit ctx: SignallingContext[SinkProfile]): Option[(LOAD, String)] = {
					var r = delivered.get(idx)
					if (r nonEmpty) {
						if (pending nonEmpty) {
							val next = pending.dequeue
							delivered += idx -> next
							sink.loadArrived(this, next._1, Some(idx) )
						} else {
							openSlots += idx
						}
						ctx.signalSelf(ch.loadPullBuilder(r.head._1,idx))
					}
					r
				}

				override def peekNext: Option[(LOAD, String)] = delivered.headOption.flatMap { e => peek(e._1) }
				override def peek(l: LOAD): Option[(LOAD, String)] = delivered.find(e => e._2._1 == l).flatMap(e => peek(e._1))
				override def peek(idx: Int): Option[(LOAD, String)] = delivered.get(idx)

				private def acknowledgeLoad(load: LOAD)(implicit ctx: SignallingContext[SinkProfile]): Unit = {
					log.debug(s"Start Acknowledge Load $load with endpoint ${_start}, from $delivered")
					for {
						st <- _start
						(idx, (ld, rs)) <- delivered.find(p => p._2._1.uid == load.uid)
					} yield {
						log.debug(s"Releasing $load to ${st.source.ref} with $rs")
						ctx.signal(st.source.ref, ch.acknowledgeBuilder(ch.name, load, rs))
						delivered.remove(idx)
					}
				}
				def doEndpointReceiving(load: LOAD, resource: String)(implicit ctx: SignallingContext[SinkProfile]): Option[Int] = {
					log.debug(s"Processing Transfer Load ${load} on channel ${ch.name}")
					if (openSlots nonEmpty) {
						val idx = openSlots.head
						openSlots -= idx
						delivered += idx -> (load, resource)
						log.debug(s"Processing Transfer Load ${load} on channel ${ch.name}: Available Slot for delivery. Delivered $delivered, Queued: $pending")
						Some(idx)
					} else {
						log.debug(s"Processing Transfer Load ${load} on channel ${ch.name}: Not available slot, queuing. Delivered $delivered, Queued: $pending")
						pending.enqueue((load -> resource))
						None
					}
				}
				override def loadReceiver: Processor.DomainRun[SinkProfile] = {
					implicit ctx: SignallingContext[SinkProfile] => {
						case tr: Channel.TransferLoad[LOAD] if tr.channel == ch.name =>
							doEndpointReceiving(tr.load, tr.resource).map(i => sink.loadArrived(this, tr.load, Some(i))).getOrElse(DomainRun.same)
						case tr: PulledLoad[LOAD] if tr.channel == ch.name =>
							log.debug(s"Processing PulledLoad with ${tr.load}")
							acknowledgeLoad(tr.load)
							sink.loadReleased(this, tr.load, Some(tr.idx))
					}
				}

			}
		}
		/*	def simpleDelayChannel[L <: Identification, SourceProfile, SinkProfile](name: String, delay: Delay, capacity: Int, boundedLookup: Option[Int] = None) =
		new Channel[L, SourceProfile, SinkProfile]((1 to capacity).map(_ => java.util.UUID.randomUUID.toString).toSet, () => Some(delay), boundedLookup, name)*/

	}

}

abstract class Channel[LOAD <: Identification, SourceProfile >: ChannelConnections.ChannelSourceMessage, SinkProfile >: ChannelConnections.ChannelDestinationMessage]
(val delay: () => Option[Delay], val cards: Set[String], val configuredOpenSlots: Int = 1, val name:String = java.util.UUID.randomUUID().toString)
	extends Identification.Impl(name) {
	type TransferSignal <: Channel.TransferLoad[LOAD]
	type PullSignal <: Channel.PulledLoad[LOAD]

	def transferBuilder(channel: String, load: LOAD, resource: String): TransferSignal
	def loadPullBuilder(ld: LOAD, idx: Int): PullSignal

	type AckSignal <: Channel.AcknowledgeLoad[LOAD]

	def acknowledgeBuilder(channel: String, load: LOAD, resource: String): AckSignal

}
