/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.unitsorter

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.channels.Channel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.base.Processor._
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.equipment.elements.XSwitchTransfer.Transfer
import com.saldubatech.base.layout.Geography.{ClosedPathPoint, Length}
import com.saldubatech.base.layout.TaggedGeography
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.utils.Boxer._

import scala.concurrent.duration._

class CircularSorterSingleTaskSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import SpecActorHarness._


	def this() = this(ActorSystem("CircularSorterSingleTaskSpec"))


	implicit val nTrays: Length = Length(12)

	val inducts: List[DirectedChannel[Material]] = List(DirectedChannel(4, "North"), DirectedChannel(4, "South"))
	val discharges: List[DirectedChannel[Material]] = List(DirectedChannel(4, "TWO"), DirectedChannel(4, "TEN"))
	val geoTags: Map[DirectedChannel.Endpoint[Material], ClosedPathPoint] = Map(
		inducts.head.end -> new ClosedPathPoint(0),
		inducts(1).end -> new ClosedPathPoint(6),
		discharges.head.start -> new ClosedPathPoint(2),
		discharges(1).start -> new ClosedPathPoint(10)
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


	val material1 = Material("M1")
	val material2 = Material("M2")
	val material3 = Material("M3")
	def receiveAndAcknowledgeLoad(channel: DirectedChannel[Material], material: Material): HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
		case t: TransferLoad[Material] if t.channel == channel.uid && t.load.uid == material.uid =>
			host.log.info(s"${host.self.path.name} Received Transfer through ${t.channel} from $from")
			channel.end.loadReceiving(from,at).apply(t)
			channel.end.doneWithLoad(t.load, at)
	}

	// High Level Scenario
	// First Load
	//     Send Load to North Induct
	//     Get "ReceiveLoad"
	//     Send Command to deliver at TEN
	//     Expect Staging at: 0 (all trays are empty) on Tray #1
	//     Expect Load at TEN --> When --> 10*40/10 --> 40
	// Second Load
	//     Send Command to Take From Induct SIX and Deliver to Discharge TWO
	//     Send Load to SIX Induct with 240 delay
	//     Expect Staging at: 0 Delay (all trays are empty) on Tray #?? -> time of Loading = 40+240 -> 280
	//             --> Distance = 280*10 == 2800 --> 70 Trays have passed --> 70%12 = 10 Tray0Position -->
	//             --> Tray 9 at SIX -->
	//     Expect Load at TWO Travel Time from SIX to TWO = 8 * 40/10 = 32 --> at time 280 + 32  = 312

	val startTime: Int = 0
	val firstDeliveryTime: Int = 40
	val delayToSendSecond: Int = 320
	val secondStageTime: Int = firstDeliveryTime+ delayToSendSecond
	val secondDelivery: Int = secondStageTime + 32
	val sendFirstLoadMsg: String = "SendFirstLoad"
	val controllerKickOff: HarnessTrigger = (host, from, at) => {
		implicit val h = host
		sendFirstLoadMsg ~> environmentHarness now at
	}
	val sendFirstLoadFromEnv: HarnessStep = (host, from, at) => {
		case s: String if s == sendFirstLoadMsg =>
			inducts.head.start.sendLoad(material1, at)
	}
	val discardAcknowledgeFirstLoad: HarnessStep = nopStep("Acknowledge Material1")

	val firstTransferCmd = Transfer(inducts.head.end, discharges(1).start, material1.uid.?)

	val expectStartFirstTask: HarnessStep = (host, from, at) => {
		case StartTask(cmdId, materials) if cmdId == firstTransferCmd.uid  && materials.head == material1 && at == 0 =>
	}
	val controllerGetsReceiveLoad: HarnessStep = (host, from, at) => {
		case ReceiveLoad(fromVia, mat) if fromVia == inducts.head.end && mat == material1 =>
			implicit val h = host
			firstTransferCmd ~> underTest now at
	}

	val expectStagingFirstLoad: HarnessStep = (host, from, at) => {
		case StageLoad(cmdId, mat) if at == startTime && cmdId == firstTransferCmd.uid && mat.! == material1 =>
	}

	val expectDeliverFirstLoad: HarnessStep = (host, from, at) => {
		case DeliverResult(cmdId, via, load)
			if cmdId == firstTransferCmd.uid && at == firstDeliveryTime && via == discharges(1).start && load == material1 =>
	}
	val envReceiveFirstLoad = receiveAndAcknowledgeLoad(discharges(1), material1)
	val sendSecondLoadMsg = "sendSecondLoad"
	val secondTransferCmd = Transfer(inducts(1).end, discharges.head.start, material2.uid.?)
	val expectCompleteFirstCommand: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdId, mats, results)
			if at == 40 && cmdId == firstTransferCmd.uid && mats.head == material1 && results.head == material1 =>
			implicit val h = host
			secondTransferCmd ~> underTest now at
			sendSecondLoadMsg ~> environmentHarness in ((at, delayToSendSecond))
	}

	val sendSecondLoadFromEnv: HarnessStep = (host, from, at) => {
		case s: String if s == sendSecondLoadMsg && at == secondStageTime =>
			inducts(1).start.sendLoad(material2, at)
	}
	val discardAcknowledgeSecondLoad: HarnessStep = nopStep("Received second load Acknowledgement material2")
	val controllerGetsReceiveLSecondLoad: HarnessStep = (host, from, at) => {
		case ReceiveLoad(fromVia, mat) if fromVia == inducts(1).end && mat == material2 && at == secondStageTime =>
	}
	val expectStartSecondTask: HarnessStep = (host, from, at) => {
		case StartTask(cmdId, materials)
			if cmdId == secondTransferCmd.uid  && materials.head == material2 && at == secondStageTime =>
	}

	val expectStagingSecondLoad: HarnessStep = (host, from, at) => {
		case StageLoad(cmdId, mat) if at == secondStageTime && cmdId == secondTransferCmd.uid && mat.! == material2 =>
	}

	val expectDeliverSecondLoad: HarnessStep = (host, from, at) => {
		case DeliverResult(cmdId, via, load)
			if cmdId == secondTransferCmd.uid && at == secondDelivery && via == discharges.head.start && load == material2 =>
	}
	val envReceiveSecondLoad = receiveAndAcknowledgeLoad(discharges.head, material2)
	val expectCompleteSecondCommand: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdId, mats, results)
			if at == secondDelivery && cmdId == secondTransferCmd.uid && mats.head == material2 && results.head == material2 =>
	}

	val envTrigger: HarnessTrigger = nopTrigger
	val envActions: Seq[HarnessStep] = Seq(
		sendFirstLoadFromEnv,
		discardAcknowledgeFirstLoad,
		envReceiveFirstLoad,
		sendSecondLoadFromEnv,
		discardAcknowledgeSecondLoad,
		envReceiveSecondLoad
	)
	val envObserver: TestProbe = TestProbe()
	val envConfigurer: HarnessConfigurer = host => {
		case _ =>
			inducts.foreach(c => c.registerStart(host.asInstanceOf[DirectedChannel.Source[Material]]))
			discharges.foreach(c => c.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]]))
	}
	class EnvHarness
		extends SpecActorHarness(envTrigger, envActions, "environmentHarness", gw,
			envObserver.testActor.?, envConfigurer)
			with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {
		val name = uid
		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}
		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {}
	}
	val environmentHarness: ActorRef = gw.simActorOf(Props(new EnvHarness()), "environmentHarness")

	val controllerActions: Seq[HarnessStep] = Seq(
		controllerGetsReceiveLoad,//0
		expectStartFirstTask,//1
		expectStagingFirstLoad,//2
		expectDeliverFirstLoad,//3
		expectCompleteFirstCommand,//4
		controllerGetsReceiveLSecondLoad,//5
		expectStartSecondTask,//6
		expectStagingSecondLoad,//7
		expectDeliverSecondLoad,//8
		expectCompleteSecondCommand//9
	)
	val controllerObserver = TestProbe()
	val controllerConfigurer: SpecActorHarness => Configuring = nopConfigure

	class ControllerHarness
		extends SpecActorHarness(controllerKickOff, controllerActions, "controllerHarness", gw,
			controllerObserver.testActor.?, controllerConfigurer)
	val controller: ActorRef = gw.simActorOf(Props(new ControllerHarness()), "controllerHarness")


	gw.configure(controller, "ConfigureController")
	gw.configure(environmentHarness, "ConfigureDownstream")
	gw.configure(underTest, ConfigureOwner(controller))

	gw.injectInitialAction(environmentHarness, KickOff())
	gw.injectInitialAction(controller, KickOff())

	gw.activate()



	"The CircularSorter Executor" should {
		"transfer a load from an Induct to a Discharge" when {
			"receiving a transfer command" in {
				envObserver.expectMsg(sendFirstLoadMsg)
				controllerObserver.expectMsg(ReceiveLoad(inducts.head.end, material1))
				envObserver.expectMsgClass(max = 1000 millis, classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(StartTask(firstTransferCmd.uid, Seq(material1)))
				controllerObserver.expectMsg(StageLoad(firstTransferCmd.uid, material1.?))
				envObserver.expectMsgClass(classOf[TransferLoad[Material]])
				controllerObserver.expectMsg(DeliverResult(firstTransferCmd.uid, discharges(1).start, material1))
				controllerObserver.expectMsg(CompleteTask(firstTransferCmd.uid, Seq(material1), Seq(material1)))

				envObserver.expectMsg(sendSecondLoadMsg)
				controllerObserver.expectMsg(ReceiveLoad(inducts(1)end, material2))
				envObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(StartTask(secondTransferCmd.uid, Seq(material2)))
				controllerObserver.expectMsg(StageLoad(secondTransferCmd.uid, material2.?))
				envObserver.expectMsgClass(classOf[TransferLoad[Material]])
				controllerObserver.expectMsg(DeliverResult(secondTransferCmd.uid, discharges.head.start, material2))
				controllerObserver.expectMsg(CompleteTask(secondTransferCmd.uid, Seq(material2), Seq(material2)))
				envObserver.expectNoMessage(50 millis)
				controllerObserver.expectNoMessage(50 millis)
			}
		}
	}
}
