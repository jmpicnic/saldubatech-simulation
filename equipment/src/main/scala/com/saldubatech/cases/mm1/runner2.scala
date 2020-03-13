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

package com.saldubatech.cases.mm1

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import com.saldubatech.base.channels.v1.AbstractChannel.{ConfigureLeftEndpoints, ConfigureRightEndpoints}
import com.saldubatech.base.Material
import com.saldubatech.base.channels.v1.OneWayChannel
import com.saldubatech.ddes.Gateway
import com.saldubatech.equipment.elements.SimpleRandomExecution.ConfigureOwner
import com.saldubatech.equipment.elements.{Discharge, SimpleRandomExecution, StepProcessor}
import com.saldubatech.equipment.generic.SimpleServer
import com.saldubatech.equipment.generic.Source.Activate
import com.saldubatech.events.{DBEventSpooler, EventStore}
import com.saldubatech.randomvariables.Distributions
import com.saldubatech.system.EquipmentSimulationHarness2

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object runner2 {
	val fifoJobSelectionPolicy: StepProcessor.JobSelectionPolicy = new StepProcessor.JobSelectionPolicy {
		override def prioritizeJobs(queue: ListBuffer[Material]): List[Material] = queue.toList
	}

	val fifoDeliveryPolicy: StepProcessor.DeliveryPolicy = new StepProcessor.DeliveryPolicy {
		override def prioritize(finishedGoods: List[(String, Material)]): mutable.Queue[(String, Material)] =
			mutable.Queue() ++= finishedGoods
	}

	def fixedOutboundSelector(channelName: String): Discharge.SelectionPolicy = {
		new Discharge.SelectionPolicy {
			override def dischargeSelection(load: Material, outQueues: Map[String, OneWayChannel.Endpoint[Material]]): String = {
				assert(outQueues contains channelName)
				channelName
			}
		}
	}
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

	def buildM1Server(gw: Gateway,
	                  maxProcessingRate: Double,
	                  dischargeChannel: String): (ActorRef, ActorRef) = {

		val serverExecutor = gw.simActorOf(Props(new SimpleRandomExecution("M1_Executor",
			gw, Distributions.discreteExponential(maxProcessingRate))), "M1_Executor")

		val server = gw.simActorOf(SimpleServer.props("Xm1_Server",
			gw,
			1,
			serverExecutor,
			fifoJobSelectionPolicy,
			fifoDeliveryPolicy,
			fixedOutboundSelector(dischargeChannel),
		), "Xm1_server")
		gw.configure(serverExecutor, ConfigureOwner(server))
		(server, serverExecutor)
	}

	//Option[() => Unit]
	def shutdown(actorsToKill: ActorRef*): () => Unit = {
		() => {
			actorsToKill.foreach(_ ! PoisonPill)
		}
	}

	def main(args: Array[String]): Unit = {
		// Set up actor system
		val system = ActorSystem("MM1")
		//, config)

		val store = EventStore("casesDB", "m_m_1_d") // "CasesDB" defined in application.conf
		store.refresh
		val spooler = Some(DBEventSpooler(store))

		val gw = new Gateway(system, spooler)

		// Create the nodes
		val (harness, executor) = buildHarness(gw, 1e7.toLong, 100.0)

		val (server, serverExecutor) = buildM1Server(gw, 80.0, "dischargeChannel")

		gw.installShutdown(shutdown(harness.sink, server, serverExecutor, harness.source, executor))

		// Create the induct channel
		val inductChannel = new OneWayChannel[Material](100, "inductChannel")
		val dischargeChannel = new OneWayChannel[Material](100, "dischargeChannel")
		gw.configure(harness.source, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(inductChannel)))
		gw.configure(server, ConfigureLeftEndpoints[OneWayChannel[Material]](Seq(dischargeChannel)), ConfigureRightEndpoints[OneWayChannel[Material]](Seq(inductChannel)))
		gw.configure(harness.sink, ConfigureRightEndpoints[OneWayChannel[Material]](Seq(dischargeChannel)))

		gw.injectInitialAction(harness.source, Activate())

		gw.activate()

		//gw.shutdown()
	}
}

