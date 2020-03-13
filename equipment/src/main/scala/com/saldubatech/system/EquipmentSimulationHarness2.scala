/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.system

import akka.actor.ActorRef
import com.saldubatech.base.Material
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.elements.SimpleRandomExecution.ConfigureOwner
import com.saldubatech.equipment.elements.{Discharge, StepProcessor}
import com.saldubatech.equipment.generic.{Sink, Source}

import scala.collection.mutable.ListBuffer

class EquipmentSimulationHarness2(name: String, gw: Gateway,
                                  capacity: Int,
                                  sourceExecutor: ActorRef,
                                  loadGenerator: Long => Option[Material],
                                  endCondition: (Option[Material], Long) => Boolean,
                                  deliveryPolicy: StepProcessor.DeliveryPolicy,
                                  outboundSelector: Discharge.SelectionPolicy,
                                  jobSelectionPolicy: StepProcessor.JobSelectionPolicy = new StepProcessor.JobSelectionPolicy {
	                                 override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] = {
		                                 queue.toList
	                                 }
                                 }) {


	val source: ActorRef = gw.simActorOf(Source.props(
		name+"_Source",
		gw,
		capacity,
		sourceExecutor,
		loadGenerator,
		endCondition,
		deliveryPolicy,
		outboundSelector,
		jobSelectionPolicy
	), name+"_Source")
	gw.configure(sourceExecutor, ConfigureOwner(source))

	val sink: ActorRef = gw.simActorOf(Sink.props(name+"_Sink",gw), name+"_Sink")//name+"_Sink", gw, localCollector))

}