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

package com.saldubatech.v1.equipment.generic

import akka.actor.{ActorRef, Props}
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.v1.OneWayChannel
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl, SimMessage}
import com.saldubatech.v1.equipment.elements.{Discharge, StepProcessor}
import com.saldubatech.v1.events.{Event, OperationalEvent}
import com.saldubatech.util.Lang._

import scala.collection.mutable.ListBuffer

object Source {
	case class Deactivate()
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
	case class Activate()
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)

	def props(
		         name: String,
		         gw: Gateway,
		         capacity: Int,
		         executor: ActorRef,
		         loadGen: Long => Option[Material],
		         endCondition: (Option[Material], Long) => Boolean,
		         deliveryPolicy: StepProcessor.DeliveryPolicy,
		         outboundSelector: Discharge.SelectionPolicy,
		         jobSelectionPolicy: StepProcessor.JobSelectionPolicy = new StepProcessor.JobSelectionPolicy {
	             override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] = queue.toList
             }): Props =
		Props(new Source(name, gw, capacity, executor, loadGen, endCondition, deliveryPolicy, outboundSelector, jobSelectionPolicy))


	val neverEnd: (Option[Material], Long) => Boolean = (_,_) => true
}


class Source(val name: String, gw: Gateway,
             val p_capacity: Int,
             val p_executor: ActorRef,
             val loadGenerator: Long => Option[Material],
             val endCondition: (Option[Material], Long) => Boolean,
             val p_deliveryPolicy: StepProcessor.DeliveryPolicy,
             val p_outboundSelector: Discharge.SelectionPolicy,
             val p_jobSelectionPolicy: StepProcessor.JobSelectionPolicy = StepProcessor.fifoSelector
            )
	extends SimActorImpl(name, gw)
		with  Discharge
		with StepProcessor
		with OneWayChannel.Destination[Material] {
	import Source._

	override protected def induct: StepProcessor.Induct = new StepProcessor.Induct{
		def consumeInput(operation: Material, at: Long): Unit = {
			log.debug(s"Got Consumed Input at $at for material: ${operation.uid}")
			injectLoad(at)
		}
	}

	var active: Boolean = false

	protected def injectLoad(at: Long): Unit = {
		if(active) {
			val load:Option[Material] = loadGenerator(at)
			if (endCondition(load, at)) {
				log.debug(s"Completed run at $at, discarded load ${load.get.uid} ")
				active = false
			} else {
				log.debug(s"$name Injecting load ${load.get}")
				collect(Event(at, OperationalEvent.New, name, load.get.uid))
				collect(Event(at, OperationalEvent.Arrive, name, load.get.uid))
				newJobArrival(load.!, at)
			}
		}
	}


	def sourcing(from: ActorRef, at: Long): Processing = {
		case Activate() =>
			active = true
			for(i <- 1 to p_capacity) {
				injectLoad(at)
			}
		case Deactivate() =>
			active = false
	}

	override def configure: Configuring = dischargeConfiguring
	override def process(from: ActorRef, at: Long): Processing =
		sourcing(from, at) orElse processing(from, at) orElse discharging(from, at)

	override def onAccept(via: OneWayChannel.Endpoint[Material], load: Material, at: Long): Unit = {}
}
