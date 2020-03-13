/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.typed.ActorRef

import scala.collection.mutable


trait Monitored[M, MMsg <: M] {
	type ACTOR  = ActorRef[M]
	private val wkw: ActorRef[M] = null
	protected val subscriptionMessage: Set[ACTOR] => MMsg
	private val monitors = mutable.Set.empty[ACTOR]

	def add(m: ACTOR) = {
		monitors += m
		notifyObservers(subscriptionMessage(monitors.toSet))
	}

	def remove(m: ActorRef[M]) = {
		monitors -= m
		notifyObservers(subscriptionMessage(monitors.toSet))
	}

	def notifyObservers(m: M*): Unit = for {
		target <- monitors
		msg <- m
	} target ! msg
}
