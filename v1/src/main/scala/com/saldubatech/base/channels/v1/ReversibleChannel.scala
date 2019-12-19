/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.channels.v1

import akka.actor.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.base.channels.v1.AbstractChannel.FlowDirection
import com.saldubatech.base.resource.DiscreteResourceBox
import com.saldubatech.ddes.SimActor.{Processing, nullProcessing}
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.util.Lang._

object ReversibleChannel {
	def apply[L <: Identification](capacity: Int, name: String = java.util.UUID.randomUUID().toString) =
		new ReversibleChannel[L](capacity, name)

	case class RequestDirectionSwitch(channelName: String)
	case class ConfirmDirectionSwitch(channelName: String)

	trait Destination[L <: Identification] extends AbstractChannel.Destination[L, ReversibleChannel.Endpoint[L]] {
		protected def processingBuilder: (ActorRef, Long) => Processing = (from: ActorRef, tick: Long) =>
			rightEndpoints.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
				.orElse(leftEndpoints.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p))
				.orElse(leftEndpoints.values.map(endPoint => endPoint.loadReceiving(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)
				.orElse(rightEndpoints.values.map(endPoint => endPoint.restoringResource(from, tick)).fold(nullProcessing)((acc, p) => acc orElse p)))
	}

	private object Role extends Enumeration {
		val SENDER, RECEIVER, SWITCHING = Value
	}

	/**
		* By convention initialRole = SENDER for Side = Left.
		*/
	class Endpoint[L <: Identification](name: String, side: FlowDirection.Value, sendingResources: DiscreteResourceBox, receivingResources: DiscreteResourceBox)
		extends AbstractChannel.Endpoint[L, ReversibleChannel.Endpoint[L]](name, side, sendingResources, receivingResources) {

		override def myself: ReversibleChannel.Endpoint[L] = this

		private var role = if(side == FlowDirection.UPSTREAM) Role.SENDER else Role.RECEIVER

		private var continuation: Option[Endpoint[L] => Unit] = None

		def processSwitchRequest(from: ActorRef, at: Long): Processing = {
			case RequestDirectionSwitch(channelName) if channelName == name && from == peerOwner.! =>
				role = Role.SWITCHING
				allowSwitchingIfReady(from, at)
		}

		def processAllowSwitching(from: ActorRef, at: Long): Processing = {
			case ConfirmDirectionSwitch(channelName) if channelName == name =>
				role = Role.SENDER
				if(continuation isDefined) {
					continuation.!.apply(this)
					continuation = None
				}
				owner.onRestore(this, at)
		}

		def isSender: Boolean = role == Role.SENDER

		/**
			* Request a switch if Receiver. Don't allow to request switch if already Switching.
			*
			* @param at Sim Time Tick
			* @param contAction A function to be invoked as soon as the switch is available.
			* @return true if already allowed to send, false if need to wait for message and "onRestore" callback
			*/
		def requestSendingRole(at: Long, contAction: Option[Endpoint[L] => Unit] = None): Boolean = {
			if (role == Role.RECEIVER) {
				owner.log.debug(s"Requesting Sending Role at $at")
				role = Role.SWITCHING
				continuation = contAction
				//owner.! send RequestDirectionSwitch(name) _to peerOwner.! now at
				RequestDirectionSwitch(name) ~> peerOwner.! now at
				false
			} else if(role == Role.SWITCHING) false
			else true
		}

		/**
			* Will only allow sending if in sender role. Not while switching or in receiving mode.
			* @param load The material sent
			* @param at SimTime Tick
			* @return
			*/
		override def sendLoad(load: L, at: Long): Boolean = {
			if (role == Role.SENDER) super.sendLoad(load, at)
			else false
		}

		override def doLoadReceiving(from: ActorRef, tick: Long, load: L, resource: String): Unit = {
			assert(role == Role.RECEIVER, "Cannot receive when not in RECEIVER role")
			super.doLoadReceiving(from, tick, load, resource)
		}

		override def doRestoreResource(from: ActorRef, tick: Long, resource: String): Unit = {
			super.doRestoreResource(from, tick, resource)
			allowSwitchingIfReady(from, tick)
		}

		private def allowSwitchingIfReady(peer: ActorRef, at: Long): Boolean = {
			if(role == Role.SWITCHING && sendingResourceBox.isFull) {
				owner.log.debug(s"Confirming Direction Switch to ${peer.path.name} at $at")
				//owner.! send ConfirmDirectionSwitch(name) _to peer now at
				ConfirmDirectionSwitch(name) ~> peer now at
				role = Role.RECEIVER
				true
			} else false
		}

	} // End Endpoint

}


class ReversibleChannel[L <: Identification](capacity: Int,
                                name: String = java.util.UUID.randomUUID().toString)
extends AbstractChannel[L, ReversibleChannel.Endpoint[L]](capacity, name){

	override protected def buildEndpoint(side: FlowDirection.Value): ReversibleChannel.Endpoint[L] =
		new ReversibleChannel.Endpoint[L](name, side, resources(side), resources(side.opposite))


}
