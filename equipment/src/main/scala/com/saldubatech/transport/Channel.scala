/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import akka.actor.typed.ActorRef
import com.saldubatech.base.{AssetBox, Identification}
import com.saldubatech.ddes.Clock.{Delay, Tick}
import com.saldubatech.ddes.Processor
import com.saldubatech.ddes.Processor.{CommandContext, ProcessorMessage, ProcessorRef}
import com.saldubatech.ddes.Simulation.{Command, Notification}
import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object Channel {

	trait AcknowledgeLoad[L <: Identification]{val channel: String; val load: L; val resource: String}
	trait ReceiverSignal[L]
	trait TransferLoad[L <: Identification] extends ReceiverSignal[L] {val channel: String; val load: L; val resource: String}
	trait DequeueNextLoad[L <: Identification] extends ReceiverSignal[L] {val channel: String}

	case class ReceivingConfiguration[L <: Identification](boundedLookup: Option[Int] = None, transferBuilder: (String, L, String) => TransferLoad[L], releaseBuilder: String => DequeueNextLoad[L])
	case class SendingConfiguration[L <: Identification](delay: () => Option[Delay], acknowledgeBuilder: (String, L, String) => AcknowledgeLoad[L])

/*	def simpleDelayChannel[L <: Identification, StartProfile, EndProfile](name: String, delay: Delay, capacity: Int, boundedLookup: Option[Int] = None) =
		new Channel[L, StartProfile, EndProfile]((1 to capacity).map(_ => java.util.UUID.randomUUID.toString).toSet, () => Some(delay), boundedLookup, name)*/

}

class Channel[L <: Identification, StartProfile, EndProfile]
(slots: Set[String], name:String = java.util.UUID.randomUUID().toString, sender: Channel.SendingConfiguration[L], receiver: Channel.ReceivingConfiguration[L])
	extends Identification.Impl(name) with LogEnabled {
	import Channel._


	trait Endpoint[DomainMessage] {
		val channelName: String

		private var _hostValue: Option[Processor.Running[DomainMessage]] = None
		def hostValue = _hostValue
		lazy val host:Option[Processor.Running[DomainMessage]] = hostValue

		private[Channel] lazy val consume: Option[L => Unit] = setConsume
		private def setConsume = _consume
		private var _consume: Option[L => Unit] = None

		def register(hostParameter: Processor.Running[DomainMessage], consumeParameter: L => Unit) =
			if (_consume nonEmpty) throw new IllegalStateException(s"Start is already configured")
			else {
				_consume = Some(consumeParameter)
				_hostValue = Some(hostParameter)
			}
	}

	class Start(override val channelName: String, slots: Set[String], private[Channel] val config: SendingConfiguration[L])
		extends Endpoint[StartProfile]{
		private val localBox = AssetBox(slots, channelName + "Start")

		def availableSlots: Int = localBox.available

		def send(load: L)(implicit ctx: CommandContext[StartProfile]): Boolean = {(
				for {
					h <- host
					prH <- end.host
					prR <- prH.ref
					card <- localBox.checkoutAny
				} yield h.tellTo(receiver.transferBuilder(channelName, load, card), prR, config.delay())).isDefined
		}

		def receiveAcknowledgement: PartialFunction[StartProfile, Option[L]] = {
			case ackMsg: AcknowledgeLoad[L] if ackMsg.channel == channelName =>
				log.debug(s"Processing Load Acknowledgement for ${ackMsg.load} through $consume")
				localBox.checkin(ackMsg.resource)
				consume.map{f => f(ackMsg.load); ackMsg.load}
		}

	}

	class End(override val channelName: String, private[Channel] val config: ReceivingConfiguration[L])
		extends Endpoint[EndProfile] {

		private val wip: mutable.Map[String, String] = mutable.Map.empty

		private val pending: mutable.Queue[(L, String)] = mutable.Queue.empty

		private lazy val consumeIfPossible: (L, String) => Option[L] = config.boundedLookup match {
			case None => doReceiveLoad
			case Some(limit) => (load, resource) =>
				if (wip.contains(load.uid)) None
				else if (wip.size < limit) doReceiveLoad(load, resource)
				else {
					pending.enqueue(load -> resource)
					Some(load)
				}
		}

		private val doReceiveLoad: (L, String) => Option[L] = (load: L, resource: String) =>
			if (wip.contains(load.uid)) None // This signals a repeated send of a load.
			else {
				for {
					c <- consume
				} yield {
					wip += load.uid -> resource
					c(load)
					load
				}
			}


		def releaseLoad(load: L)(implicit ctx: CommandContext[EndProfile]): Unit = for {
			h <- host
			stH <- start.host
			stRef <- stH.ref
			rs <- wip.get(load.uid)
		} yield {
			log.debug(s"Releasing $load to $stRef with $rs")
			h.tellTo(sender.acknowledgeBuilder(channelName, load, rs), stRef)
			if(pending.nonEmpty) h.tellSelf(config.releaseBuilder(name))
		}
		def receiveLoad: PartialFunction[EndProfile, Option[L]] = {
			case tr: Channel.TransferLoad[L] if tr.channel == channelName =>
				consumeIfPossible(tr.load, tr.resource)
			case dq: DequeueNextLoad[L] if dq.channel == channelName =>
				pending.dequeueFirst(_ => true).flatMap(doReceiveLoad.tupled)
		}
	}


	lazy val start: Start = new Start(name, slots, sender)
	lazy val end: End = new End(name, receiver)
}
