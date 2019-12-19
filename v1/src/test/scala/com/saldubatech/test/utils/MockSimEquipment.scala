/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.ActorRef
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.{Gateway, SimActor, SimActorImpl}
import com.saldubatech.util.Lang._

object MockSimEquipment {

	sealed trait ExpectationResult {
		val fulfilled: Boolean
		val msg: Option[String]
	}

	case class Completion(override val fulfilled: Boolean, override val msg: Option[String] = None) extends ExpectationResult
	case class Continuation(override val fulfilled: Boolean, override val msg: Option[String] = None) extends ExpectationResult

	case class MockEquipmentContext(host: SimActor,
	                                inboundEnds: List[DirectedChannel.End[Material]],
	                                outboundStarts: List[DirectedChannel.Start[Material]])

	type MaterialExpectation = (DirectedChannel.End[Material], Material, Long, MockEquipmentContext) => ExpectationResult
	type RestoreExpectation = (DirectedChannel.Start[Material], Long, MockEquipmentContext) => ExpectationResult
	type ProtocolExpectation = (ActorRef, Long, Any, MockEquipmentContext) => ExpectationResult

	class Impl(val name: String,
	           val inbound: List[DirectedChannel[Material]],
	           val outbound: List[DirectedChannel[Material]],
	           val kickOff: (SimActor, ActorRef, Long) => Processing,
	           _protocolExpectations: List[ProtocolExpectation],
	           _materialExpectations: List[MaterialExpectation],
	           _restoreExpectations: List[RestoreExpectation],
	          )(implicit gw: Gateway)
		extends SimActorImpl(name, gw)
			with MockSimEquipment {

		override var protocolExpectations: List[ProtocolExpectation] = List(_protocolExpectations: _*)
		override protected var materialExpectations: List[MaterialExpectation] = List(_materialExpectations: _*)
		override protected var restoreExpectations: List[RestoreExpectation] = List(_restoreExpectations: _*)

		//def inContext[F](h: SimActor)(e: => F): F = e
	}
}

trait MockSimEquipment
	extends SimActor
		with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {

	import MockSimEquipment._

	final def configure: Configuring = {
		case _ =>
	}

	val inbound: List[DirectedChannel[Material]]
	val outbound: List[DirectedChannel[Material]]

	val inboundEnds: List[DirectedChannel.End[Material]] = inbound.map(_.registerEnd(this))
	val outboundStarts: List[DirectedChannel.Start[Material]] = outbound.map(_.registerStart(this))

	implicit val equipmentContext: MockEquipmentContext = MockEquipmentContext(this, inboundEnds, outboundStarts)

	override final def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
		checkMaterialReceived(via, load, tick)
	}

	override final def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {
		log.debug(s"Restoring Channel Capacity: $via at $tick")
		checkRestoreReceived(via, tick)
	}

	final def process(from: ActorRef, at: Long): Processing =
		kickOff(this, from, at) orElse ioProcessing(from, at) orElse protocolProcessing(from, at)

	def kickOff: (SimActor, ActorRef, Long) => Processing

	private def ioProcessing(from: ActorRef, at: Long): Processing =
		inboundEnds.map(_.loadReceiving(from, at)).fold(nullProcessing) { (acc, e) => acc orElse e } orElse
			outboundStarts.map(_.restoringResource(from, at)).fold(nullProcessing) { (acc, e) => acc orElse e }

	private def protocolProcessing(from: ActorRef, at: Long): Processing = {
		case a: Any =>
			protocolExpectationProcessing(from, at, a)
	}

	var protocolExpectations: List[ProtocolExpectation]
	def protocolExpectationProcessing(from: ActorRef, at: Long, a: Any): Unit = {
		if(protocolExpectations nonEmpty)
			protocolExpectations = verifyExpectationResult(protocolExpectations,
				protocolExpectations.head(from, at, a, equipmentContext))
	}

	protected var materialExpectations: List[MaterialExpectation]
	private def checkMaterialReceived(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
		if(materialExpectations nonEmpty) {
			materialExpectations = verifyExpectationResult(materialExpectations,
				materialExpectations.head(via, load, tick, equipmentContext))
			via.doneWithLoad(load, tick)
		}
	}

	protected var restoreExpectations: List[RestoreExpectation]
	private def checkRestoreReceived(via: DirectedChannel.Start[Material], tick: Long): Unit = {
		if(restoreExpectations nonEmpty)
			restoreExpectations = verifyExpectationResult(restoreExpectations,
				restoreExpectations.head(via, tick, equipmentContext))
	}

	private def verifyExpectationResult[T](l: List[T], r: ExpectationResult): List[T] = {
		assert(r.fulfilled, r.msg.headOption)
		if (l isEmpty) l else {
			r match {
				case Continuation(success, msgOption) => l
				case Completion(success, msgOption) => l.tail
			}
		}
	}
}
