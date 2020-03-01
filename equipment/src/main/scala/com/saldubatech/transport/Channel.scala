/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.base.{AssetBox, Identification}
import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.CommandContext
import com.saldubatech.util.LogEnabled
import com.saldubatech.v1.base.channels.Channel.AcknowledgeLoad

import scala.collection.mutable

object Channel {

	trait AcknowledgeLoad[L <: Identification]{val channel: String; val load: L; val resource: String}
	abstract class AckLoadImpl[L <: Identification](override val channel: String, override val load: L, override val resource: String)
		extends Identification.Impl() with AcknowledgeLoad[L]
	trait ReceiverSignal[L]
	trait TransferLoad[L <: Identification] extends ReceiverSignal[L] {val channel: String; val load: L; val resource: String}
	abstract class TransferLoadImpl[L <: Identification](override val channel: String, override val load: L, override val resource: String)
		extends Identification.Impl() with TransferLoad[L]
	trait LoadConsumed[L <: Identification] extends ReceiverSignal[L] {val channel: String}
	abstract class LoadConsumedImpl[L <: Identification](override val channel: String)
		extends Identification.Impl() with LoadConsumed[L]

	trait Sink[L <: Identification, SinkProfile]{
		type TransferSignal = TransferLoad[L] with SinkProfile
		type ConsumeSignal = LoadConsumed[L] with SinkProfile
		def transferBuilder(channel: String, load: L, resource: String): TransferSignal
		def releaseBuilder(channel: String): ConsumeSignal

		def loadArrived(endpoint: End[L, SinkProfile], load: L)(implicit ctx: CommandContext[SinkProfile]): Boolean
	}

	trait Source[L <: Identification, SourceProfile]{
		type Signal = SourceProfile with AcknowledgeLoad[L]
		def acknowledgeBuilder(channel: String, load: L, resource: String): Signal
		def loadAcknowledge(load: L): Option[L]
	}

	trait Endpoint[DomainMessage] {
		val channelName: String
		private var _hostValue: Option[Processor.ProcessorRef] = None
		private def hostValue = _hostValue
		lazy val host: Option[Processor.ProcessorRef] = hostValue

		def register(hostParameter: Processor.ProcessorRef) =
			if (_hostValue nonEmpty) throw new IllegalStateException(s"Start is already configured")
			else _hostValue = Some(hostParameter)
	}

	trait Start[LOAD, SourceProfile] extends Endpoint[SourceProfile]{
		def availableSlots: Int
		def send(load: LOAD)(implicit ctx: CommandContext[SourceProfile]): Boolean
		def send(load: LOAD, onSlot: String)(implicit ctx: CommandContext[SourceProfile]): Boolean
		def receiveAcknowledgement: PartialFunction[SourceProfile, Option[LOAD]]
		def reserveSlot: Option[String]
	}

	trait End[LOAD, SinkProfile] extends Endpoint[SinkProfile] {
		val receivingSlots: Int
		def releaseLoad(load: LOAD)(implicit ctx: CommandContext[SinkProfile]): Unit
		def receiveLoad(msg: SinkProfile)(implicit ctx: CommandContext[SinkProfile]): Option[LOAD]
		def receiveLoad2(implicit ctx: CommandContext[SinkProfile]): PartialFunction[SinkProfile, Option[LOAD]]
	}

	class Ops[LOAD <: Identification, SourceProfile, SinkProfile](val ch: Channel, val configuredOpenSlots: Int = 1)
	                                                             (implicit sender: Channel.Source[LOAD, SourceProfile], receiver: Channel.Sink[LOAD, SinkProfile])
	extends LogEnabled {


		lazy val start: Start[LOAD, SourceProfile] = new Start[LOAD, SourceProfile]{
			override lazy val channelName = ch.name
			private val localBox = AssetBox(ch.slots, ch.name + "Start")
			private val reserved: mutable.Set[String] = mutable.Set.empty

			override def reserveSlot: Option[String] = localBox.checkoutAny.map{rs => reserved += rs; rs}

			override def send(load: LOAD, onSlot: String)(implicit ctx: CommandContext[SourceProfile]): Boolean = {(
				for {
					card <- reserved.find(_ == onSlot)
					doTell <- end.host.map(ctx.teller)
				} yield {
					reserved.remove(onSlot)
					doTell(receiver.transferBuilder(ch.name, load, card), ch.delay())
				}).isDefined
			}

			override def availableSlots: Int = localBox.available

			override def send(load: LOAD)(implicit ctx: CommandContext[SourceProfile]): Boolean = {(
				for {
					doTell <- end.host.map(ctx.teller)
					card <- localBox.checkoutAny
				} yield doTell(receiver.transferBuilder(ch.name, load, card), ch.delay())).isDefined
			}

			override def receiveAcknowledgement: PartialFunction[SourceProfile, Option[LOAD]] = {
				case ackMsg: AcknowledgeLoad[LOAD] if ackMsg.channel == ch.name =>
					log.debug(s"Processing Load Acknowledgement for ${ackMsg.load}")
					localBox.checkin(ackMsg.resource)
					sender.loadAcknowledge(ackMsg.load)
			}

		}

		lazy val end: End[LOAD, SinkProfile] = new End[LOAD, SinkProfile] {
			override lazy val channelName = ch.name
			override lazy val receivingSlots = configuredOpenSlots
			private var offeredIdx = 0L
			private val delivered: mutable.SortedMap[Long, (LOAD, String)] = mutable.SortedMap.empty
			private val pending: mutable.Queue[(LOAD, String)] = mutable.Queue.empty

			def getNext: Option[(LOAD, String)] = delivered.headOption.map(_._2)
			def get(idx: Int): Option[(LOAD, String)] = delivered.remove(idx)

			override def releaseLoad(load: LOAD)(implicit ctx: CommandContext[SinkProfile]): Unit = for {
				stRef <- start.host
				(_, (ld,rs)) <- delivered.find(p => p._2._1.uid == load.uid)
			} yield {
				log.debug(s"Releasing $load to ${stRef} with $rs")
				ctx.tellTo(stRef, sender.acknowledgeBuilder(ch.name, load, rs))
				if(pending.nonEmpty) ctx.tellSelf(receiver.releaseBuilder(ch.name))
			}

			override def receiveLoad(msg: SinkProfile)(implicit ctx: CommandContext[SinkProfile]): Option[LOAD] = msg match {
				case tr: Channel.TransferLoad[LOAD] if tr.channel == ch.name =>
					doReceive(tr.load, tr.resource)
					Some(tr.load)
				case dq: LoadConsumed[LOAD] if dq.channel == ch.name =>
					pending.dequeueFirst(_ => true).map(t => {doReceive(t._1, t._2);t._1})
			}


			override def receiveLoad2(implicit ctx: CommandContext[SinkProfile]): PartialFunction[SinkProfile, Option[LOAD]] = {
				case tr: Channel.TransferLoad[LOAD] if tr.channel == ch.name =>
					doReceive(tr.load, tr.resource)
					Some(tr.load)
				case dq: LoadConsumed[LOAD] if dq.channel == ch.name =>
					pending.dequeueFirst(_ => true).map(t => {doReceive(t._1, t._2);t._1})
			}

			private def doReceive(l: LOAD, rs: String)(implicit ctx: CommandContext[SinkProfile]): Unit = {
				if(delivered.size < receivingSlots) {
					delivered += offeredIdx -> (l, rs)
					offeredIdx += 1
					receiver.loadArrived(this, l)
				} else pending.enqueue(l -> rs)
			}
		}
	}
/*	def simpleDelayChannel[L <: Identification, SourceProfile, SinkProfile](name: String, delay: Delay, capacity: Int, boundedLookup: Option[Int] = None) =
		new Channel[L, SourceProfile, SinkProfile]((1 to capacity).map(_ => java.util.UUID.randomUUID.toString).toSet, () => Some(delay), boundedLookup, name)*/

}

class Channel(val delay: () => Option[Delay], val slots: Set[String], val name:String = java.util.UUID.randomUUID().toString)
	extends Identification.Impl(name) {
	import Channel._

}
