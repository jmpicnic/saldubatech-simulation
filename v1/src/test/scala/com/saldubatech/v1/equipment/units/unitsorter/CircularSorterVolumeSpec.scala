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

package com.saldubatech.v1.equipment.units.unitsorter

import akka.actor.{ActorRef, ActorSystem, Props}
import com.saldubatech.v1.base.processor.Processor._
import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.ddes.SimActor
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.SimDSL._
import com.saldubatech.v1.base.processor.XSwitchTransfer.Transfer
import com.saldubatech.v1.base.layout.Geography.{ClosedPathPoint, Length}
import com.saldubatech.v1.base.layout.TaggedGeography
import com.saldubatech.test.utils.MockSimEquipment._
import com.saldubatech.test.utils.{BaseActorSpec, MockSimEquipment}
import com.saldubatech.util.Lang._

import scala.concurrent.duration._
import scala.collection.mutable

class CircularSorterVolumeSpec(_system: ActorSystem) extends BaseActorSpec(_system) {


	def this() = this(ActorSystem("CircularSorterSingleTaskSpec"))


	implicit val nTrays: Length = Length(12)
	val maxLoads: Int = 1000
	val mustWait: Int = maxLoads/100
	val inboundBuffer: Int = maxLoads/8
	val initialLoadNumber: Int = Math.max(1, inboundBuffer)
	def delayer: Long = 30

	val inducts: List[DirectedChannel[Material]] = List(DirectedChannel(inboundBuffer, "North"), DirectedChannel(inboundBuffer, "South"))
	val discharges: List[DirectedChannel[Material]] = List(DirectedChannel(inboundBuffer, "TWO"), DirectedChannel(inboundBuffer, "TEN"))
	val geoTags: Map[DirectedChannel.Endpoint[Material], ClosedPathPoint] = Map(
		inducts.head.end -> ClosedPathPoint(0),
		inducts(1).end -> ClosedPathPoint(6),
		discharges.head.start -> ClosedPathPoint(2),
		discharges(1).start -> ClosedPathPoint(10)
	)

	val physics: CircularPathPhysics = CircularPathPhysics(
		nTrays = 12,
		speed = 10,
		trayLength = 40,
		tray0Location = 11)
	val geography: TaggedGeography[DirectedChannel.Endpoint[Material], ClosedPathPoint] =
		new TaggedGeography.Impl[DirectedChannel.Endpoint[Material], ClosedPathPoint](geoTags, physics)

	val underTest = CircularSorterExecution(
		"underTest",
		inducts,
		discharges,
		geography,
		physics)

	var loadsGenerated: Int = 0
	def materialGenerator(ctx: MockEquipmentContext): Option[Material] = {
		if(loadsGenerated < maxLoads) {
			val r = Material(s"Mat_$loadsGenerated ${loadsGenerated%2}_${loadsGenerated%4/2}").?
			loadsGenerated = loadsGenerated + 1
			r
		} else None
	}
	val expectedCounts: mutable.Map[DirectedChannel.Start[Material], Int] = mutable.Map(
		discharges.head.start -> 0,
		discharges(1).start -> 0
	)
	val protocolExpectations: List[ProtocolExpectation] = List(
		(from, at, msg, ctx) => {
			msg match {
				case ReceiveLoad(via, load) => ctx.host.log.debug(s"Receiving load: $load, via: $via"); Continuation(true)
				case StartTask(cmdId, materials) => ctx.host.log.debug(s"Starting transfer of $materials at $at"); Continuation(true)
				case StageLoad(cmdId, materialOption) => ctx.host.log.debug(s"Staging Load $materialOption"); Continuation(true)
				case DeliverResult(cmdId, via, result) => ctx.host.log.debug(s"Delivering Load $result to $via"); Continuation(true)
				case CompleteTask(cmdId, materials, results) => ctx.host.log.debug(s"Done with transfer of $results"); Continuation(true)
			}
		}
	)

	def sendAnotherLoad(via: DirectedChannel.Start[Material], at: Long, ctx: MockEquipmentContext): ExpectationResult = {
		val mat = materialGenerator(ctx)
		if(mat isDefined) {
			implicit val h = ctx.host
			val source = inducts(loadsGenerated % 2).end
			val destination = discharges(loadsGenerated%2).start
			assert(inducts(loadsGenerated % 2).start.sendLoad(mat.!, at, delayer), s"Overloaded channel: ${inducts(loadsGenerated % 2).start}")
			Transfer(source, destination, mat.!.uid.?) ~> underTest in ((at, delayer+1))
			expectedCounts(discharges(loadsGenerated%2).start) += 1
			Continuation(true)
		}	else Completion(true)
	}

	val restoreExpectations: List[RestoreExpectation] = List (
		(via, at, ctx) => {
			implicit val h = ctx.host
			h.log.debug(s"Restoring Induct $via at $at")
			loadsGenerated match {
				case n if n < maxLoads =>
					h.log.debug(s"Asking for another load #$n at $at")
					sendAnotherLoad(via, at, ctx)
					Continuation(true)
				case n if n == maxLoads =>
					Completion(true)
				case n if n > maxLoads =>
					Completion(false, s"Too many loads received $n".?)
			}
		}
	)

	val completedDischarges: mutable.Map[DirectedChannel.End[Material], Int] = mutable.Map(
		discharges.head.end -> 0,
		discharges(1).end -> 0
	)
	var totalDelivered: Int = 0
	val doneMessage = "AllDone"
	val materialExpectations: List[MaterialExpectation] = List(
		(via, load, at, ctx) => {
			completedDischarges(via) = 1 + completedDischarges(via)
			totalDelivered += 1
			ctx.host.log.debug(s"Received $totalDelivered Delivery of $load via $via at $at, Generated: $loadsGenerated")
			if(totalDelivered < maxLoads) Continuation(true)
			else {
				implicit val h = ctx.host
				testActor ! doneMessage
				Completion(true)
			}
		}
	)
	def sendTask(from: DirectedChannel[Material],
	             to: DirectedChannel[Material],
	             load: Option[Material],
	             at: Long,
	             cmdDelay: Long)(implicit h: SimActor): Unit = {
		if (load isDefined) {
			val cmd = Transfer(from.end, to.start, load.!.uid.?) ~> underTest
			cmdDelay match {
				case i if i > 0 =>
					from.start.sendLoad(load.!, at)
					cmd in ((at, cmdDelay))
				case i if i == 0 =>
					from.start.sendLoad(load.!, at)
					cmd now at
				case i if i < 0 =>
					from.start.sendLoad(load.!, at, -i)
					cmd now at
			}
			expectedCounts(to.start) += 1
		}
	}

	val kickOff: (SimActor, ActorRef, Long) => Processing = (host, from, at) => {
		case "GO" =>
			implicit val h = host
			(0 until initialLoadNumber).foreach {idx =>
				specLogger.debug(s"Initial Load #$idx out of ${initialLoadNumber-1} at $at")
				sendTask(inducts(idx%2), discharges(idx/4%2), materialGenerator(MockEquipmentContext(host, discharges.map(_.end), inducts.map(_.start))), at, idx%4-2)//Material(s"M_$idx")
			}
	}

	class LoadGenerator extends MockSimEquipment.Impl(
		"Load Generator",
		discharges,
		inducts,
		kickOff,
		protocolExpectations,
		materialExpectations,
		restoreExpectations//List((via, at, ctx) => Continuation(true))
	)

	"The CircularSorter Executor" should {
		"transfer a load from an Induct to a Discharge" when {
			"receiving a transfer command" in {
				val controller = gw.simActorOf(Props(new LoadGenerator()), "LoadGenerator")
				gw.configure(controller, "ConfigureController")
				gw.configure(underTest, ConfigureOwner(controller))

				gw.injectInitialAction(controller, "GO")

				gw.activate()
				expectMsg(max=mustWait seconds, doneMessage)
				completedDischarges(discharges(0).end) shouldBe expectedCounts(discharges(0).start)
				completedDischarges(discharges(1).end) shouldBe expectedCounts(discharges(1).start)
				totalDelivered shouldBe maxLoads
				loadsGenerated shouldBe maxLoads
				completedDischarges(discharges(0).end) + completedDischarges(discharges(1).end) shouldBe maxLoads
			}
		}
	}
}
