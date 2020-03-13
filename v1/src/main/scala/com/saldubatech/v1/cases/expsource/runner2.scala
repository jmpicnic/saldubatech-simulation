/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.cases.expsource

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import com.saldubatech.v1.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.v1.OneWayChannel
import com.saldubatech.v1.ddes.Gateway
import com.saldubatech.v1.equipment.elements._
import com.saldubatech.v1.equipment.generic.Source.Activate
import com.saldubatech.v1.events.{DBEventSpooler, EventStore}
import com.saldubatech.v1.randomvariables.Distributions
import com.saldubatech.v1.system.EquipmentSimulationHarness2

import scala.collection.mutable

object runner2 {

	def buildHarness(gw: Gateway, timeLimit: Long, arraivalRate: Double): (EquipmentSimulationHarness2, ActorRef) = {
		var sn = 0
		val sourceExecutor = gw.simActorOf(Props(new SimpleRandomExecution("MM1_Simulation_sourceExecutor",
			gw, Distributions.discreteExponential(arraivalRate))), "MM1_Simulation_sourceExecutor")

		(new EquipmentSimulationHarness2("MM1_Simulation",
			gw,
			1,
			sourceExecutor,
			(at: Long) => {sn += 1; Some(Material(s"Load_${sn}at$at"))},
			(mat, at) => {at >= timeLimit},
			new StepProcessor.DeliveryPolicy(){
				override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] =
					mutable.Queue() ++= finishedGoods
			},
			new Discharge.SelectionPolicy {
				override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = {
					outQueues.head._1
				}
			}
		), sourceExecutor)
	}

	//Option[() => Unit]
	def shutdown(sink: ActorRef, source: ActorRef, executor: ActorRef): () => Unit = {
		() => {
			executor ! PoisonPill
			source ! PoisonPill
			executor ! PoisonPill
		}
	}

	def main(args: Array[String]): Unit = {
		// Set up actor system
		val system = ActorSystem("MM1")
		//, config)

		val store = EventStore("casesDB", "m_m_1_c") // "CasesDB" defined in application.conf
		store.refresh
		val spooler = Some(DBEventSpooler(store))

		val gw = new Gateway(system, spooler)

		// Create the nodes
		val (harness, executor) = buildHarness(gw, 1e7.toLong, 100.0)

		gw.installShutdown(shutdown(harness.sink, harness.source, executor))
		// Create the channel
		val channel: OneWayChannel[Material] = new OneWayChannel[Material](1, "channel")
		gw.configure(harness.source, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(channel)))
		gw.configure(harness.sink, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(channel)))

		gw.injectInitialAction(harness.source, Activate())

		gw.activate()

		//gw.shutdown()
	}
}

