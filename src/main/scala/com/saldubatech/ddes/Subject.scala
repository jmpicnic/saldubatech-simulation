/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.ActorRef
import com.saldubatech.ddes.SimActor.Processing

import scala.collection.mutable
import com.saldubatech.ddes.SimDSL._

object Subject {
	trait Observer[N <: Notification] {
		def acceptNotification(msg: N, from: ActorRef)(implicit at: Long): Unit

		def notificationReceiver(from: ActorRef, at: Long): Processing = {
			case n: N if n.isInstanceOf[N] =>
				acceptNotification(n, from)(at)
		}
	}

	trait Notification extends SimMessage
	class NotificationImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends SimMessage.Impl(_id) with Notification
}

trait Subject[N <: Subject.Notification]
	extends SimActor {
	import Subject._

	protected val observers: mutable.Set[ActorRef] = mutable.Set.empty


	def registerObserver(newObserver: ActorRef): Unit = observers += newObserver
	def deregister(toRemove: ActorRef): Unit = if(observers contains toRemove) observers -= toRemove

	def notify(msg: N, at: Long): Unit = observers.foreach(o => msg ~> o now at)

}
