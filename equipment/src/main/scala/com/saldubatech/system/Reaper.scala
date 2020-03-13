/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.system

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

import scala.collection.mutable


object Reaper {
	def props(master: ActorRef): Props = Props(new Reaper(master))

	case class WatchFor(subjects: TraversableOnce[ActorRef])
}

class Reaper(master: ActorRef) extends Actor with ActorLogging {
	import Reaper._

	private val watchList = mutable.Set[ActorRef]()

	override def receive: Receive = {
		case WatchFor(subjects) =>
			subjects.foreach[Unit](ar => {watchList += ar; context.watch(ar)})
		case Terminated(aref) =>
			watchList -= aref
			if(watchList isEmpty) {
				log.info(s"Done, killing master: $master")
				master ! PoisonPill
			}
	}
}
