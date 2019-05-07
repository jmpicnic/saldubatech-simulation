/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.generic

import akka.actor.{ActorRef, Props}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn.Processing
import com.saldubatech.ddes.{Gateway, SimActor}
import com.saldubatech.equipment.elements.{Discharge, Induct, StepProcessor}

object SimpleServer {
	def props(name: String, gw: Gateway,
	          capacity: Int,
	          executor: ActorRef,
	          jobSelectionPolicy: StepProcessor.JobSelectionPolicy,
	          deliveryPolicy: StepProcessor.DeliveryPolicy,
	          outboundSelector: Discharge.SelectionPolicy): Props = Props(
		new SimpleServer(
			name, gw, capacity, executor, jobSelectionPolicy, deliveryPolicy, outboundSelector))
}
class SimpleServer(name: String, gw: Gateway,
                   val p_capacity: Int,
                   val p_executor: ActorRef,
                   val p_jobSelectionPolicy: StepProcessor.JobSelectionPolicy,
                   val p_deliveryPolicy: StepProcessor.DeliveryPolicy,
                   val p_outboundSelector: Discharge.SelectionPolicy
                   )
	extends SimActor(name,gw)
		with StepProcessor
		with Induct
		with Discharge {

	override def configure: Configuring = inductConfiguring orElse dischargeConfiguring
	override def process(from: ActorRef, at: Long): Processing =
		inducting(from, at) orElse discharging(from, at) orElse processing(from, at)

	override protected def induct: StepProcessor.Induct = this
}
