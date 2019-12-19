/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.units.grouping

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.Material
import com.saldubatech.base.Material.{DefaultPalletBuilder, TotePallet}
import com.saldubatech.base.channels.Channel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.processor.Processor._
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.ddes.{Gateway, SimActor}
import com.saldubatech.equipment.units.grouping.TotePalletizer.{GroupByIds, GroupByNumber}
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.util.Lang._
import com.typesafe.scalalogging.Logger

class PalletizerOnePalletByIdsSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import PalletizerOnePalletByNumberSpec._
	import SpecActorHarness._


	def this() = this(ActorSystem("PalletizerOnePalletByIdsSpec"))

	/**
		* The intended sequence of events after configuration is complete
		*
		* 1. Controller sends GroupByNumber with a request for 4 totes
		* 2. Controller Triggers UpstreamHarness
		* 3. UpStreamHarness sends 3 totes
		* 4. No Pallet is emitted.
		* 5. Controller triggers one more tote
		* 6. upstreamHarness sends 4th tote
		* 7. Pallet is emitted
		* 8. downstream detects pallet.
		*/

	val totes: IndexedSeq[Material] = (1 to 4).map(idx => Material(s"Tote_$idx"))
	val toteIds: IndexedSeq[String] = totes.map(_.uid)

	val inboundChannel: DirectedChannel[Material]= DirectedChannel[Material](10,"inboundChannel")
	val outboundChannel: DirectedChannel[TotePallet] = DirectedChannel[TotePallet](5, "outboundChannel")


	val underTest: ActorRef = TotePalletizer(
		name = "underTest",
		capacity = 1,
		induct = inboundChannel,
		discharge = outboundChannel,
		palletBuilder = DefaultPalletBuilder,
		perSlotCapacity = 4.?)

	val sendFirstLoads: String = "SendFirstLoads"
	val sendLastLoad: String = "SendLastLoad"


	val palletizeCmd = GroupByIds(toteIds.toList)// No slot provided

	val kickOff: HarnessTrigger = (host: SimActor, from: ActorRef, at: Long) => {
		implicit val iHost: SimActor = host
		host.log.info("Kickoff Controller, sending Outbound to underTest and triggering upstream equipment")
		palletizeCmd ~> underTest now at
		sendFirstLoads ~> upstreamEquipment in (at -> 10)
	}
	val controllerTrigger: HarnessTrigger = kickOff
	val staging3rdToteAndSend4thTote: HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
			case StageLoad(cmdId, mat) if cmdId == palletizeCmd.uid && mat.!.uid == totes(2).uid =>
				specLog.info(s"Requesting Send of Last Load.")
				implicit val iHost: SimActor = host
				sendLastLoad ~> upstreamEquipment now at
		}
	val controllerActions: Seq[HarnessStep] = Seq(
		nopStep("Receiving First Tote"),
		nopStep("Starting Command"),
		nopStep("Staging First Tote"),
		nopStep("Receiving Second Tote"),
		nopStep("Staging Second Tote"),
		nopStep("Receiving Third Tote"),
		staging3rdToteAndSend4thTote,
		nopStep("Receiving 4th Tote"),
		nopStep("Staging 4th Tote"),
		nopStep("Delivering result"),
		nopStep("Completing Command")
	)
	val controllerObserver = TestProbe()
	class ControllerHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(controllerTrigger, controllerActions, "controllerHarness", gw, controllerObserver.testActor.?, configurer)
	val controllerConfigurer: SpecActorHarness => Configuring = nopConfigure
	val controller: ActorRef = gw.simActorOf(Props(new ControllerHarness(controllerConfigurer)), "controllerHarness")

	val upstreamObserver = TestProbe()
	val upstreamEquipment: ActorRef = buildUpstreamHarness(
		List(sendFirstLoads, sendLastLoad), totes, inboundChannel, upstreamObserver)

	val downstreamObserver = TestProbe()
	val downstreamEquipment: ActorRef = buildDownstreamHarness(outboundChannel, downstreamObserver)


	"The Palletizer" should {
		"Create a pallet" when {
			"receiving 4 totes that fulfill a command" in {
					gw.configure(controller, "ConfigureController")
				gw.configure(upstreamEquipment, "ConfigureUpstream")
				gw.configure(downstreamEquipment, "ConfigureDownstream")
				gw.configure(underTest, ConfigureOwner(controller))

				gw.injectInitialAction(upstreamEquipment, KickOff())
				gw.injectInitialAction(downstreamEquipment, KickOff())
				gw.injectInitialAction(controller, KickOff())

				gw.activate()

				upstreamObserver.expectMsg(sendFirstLoads)
				controllerObserver.expectMsg(StartTask(palletizeCmd.uid, Seq()))
				controllerObserver.expectMsg(ReceiveLoad(inboundChannel.end, totes(0)))
				controllerObserver.expectMsg(StageLoad(palletizeCmd.uid, totes(0).?))
				upstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(ReceiveLoad(inboundChannel.end, totes(1)))
				controllerObserver.expectMsg(StageLoad(palletizeCmd.uid, totes(1).?))
				upstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(ReceiveLoad(inboundChannel.end, totes(2)))
				controllerObserver.expectMsg(StageLoad(palletizeCmd.uid, totes(2).?))
				upstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])

				upstreamObserver.expectMsg(sendLastLoad)
				controllerObserver.expectMsg(ReceiveLoad(inboundChannel.end, totes(3)))
				controllerObserver.expectMsg(StageLoad(palletizeCmd.uid, totes(3).?))
				upstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				downstreamObserver.expectMsgClass(classOf[TransferLoad[Material]])
				controllerObserver.expectMsgClass(classOf[DeliverResult[TotePallet]])//(DeliverResult(outboundCmd.uid, outboundChannel.start,material1))
				controllerObserver.expectMsgClass(classOf[CompleteTask])//(CompleteTask(palletizeCmd.uid,Seq(material1),Seq(material1)))
			}
		}
	}
}

object PalletizerOnePalletByIdsSpec {
	import SpecActorHarness._

	val harnessLog = Logger("PalletizerOnePalletByNumberSpec.Log")

	def receiveAndAcknowledgeLoad[M <: Material](channel: DirectedChannel[M], material: Option[M] = None): HarnessStep =
		(host: SimActor, from: ActorRef, at: Long) => {
			case t: TransferLoad[M] if t.channel == channel.uid && (material.isEmpty || t.load.uid == material.!.uid) =>
				host.log.info(s"${host.self.path.name} Received Transfer through ${t.channel} from $from")
				channel.end.loadReceiving(from,at).apply(t)
				channel.end.doneWithLoad(t.load, at)
	}

	def buildUpstreamHarness(messages: List[String],
	                         totes: IndexedSeq[Material],
	                         inboundChannel: DirectedChannel[Material],
		                       observer: TestProbe)
	                        (implicit gw: Gateway, actorSystem: ActorSystem): ActorRef = {



		val sendFirstThreeTotes: HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
			case s: String if s == messages(0) =>
				(0 until 3) foreach { idx => inboundChannel.start.sendLoad(totes(idx), at) }
		}
		val sendLastTote: HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
			case s: String if s == messages(1) =>
				harnessLog.info(s"Sending last Load at: $at")
				inboundChannel.start.sendLoad(totes(3), at)
		}

		val upstreamTrigger: HarnessTrigger = nopTrigger
		val upstreamActions: Seq[HarnessStep] = Seq(
			sendFirstThreeTotes,
			nopStep("Ack First Tote"),
			nopStep("Ack Second Tote"),
			nopStep("Ack Third Tote"),
			sendLastTote,
			nopStep("Ack Last Tote"),
		)

		val upstreamConfigurer: SpecActorHarness => Configuring = host => {
			case _ => inboundChannel.registerStart(host.asInstanceOf[DirectedChannel.Source[Material]])
		}

		class UpstreamHarness(configurer: HarnessConfigurer)
			extends SpecActorHarness(upstreamTrigger, upstreamActions, "upstreamHarness", gw, observer.testActor.?, configurer)
				with DirectedChannel.Source[Material] {
			val name: String = uid

			override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {}
		}
		gw.simActorOf(Props(new UpstreamHarness(upstreamConfigurer)), "upstreamHarness")
	}

	def buildDownstreamHarness(outboundChannel: DirectedChannel[TotePallet],
	                           observer: TestProbe)
	                          (implicit gw: Gateway, actorSystem: ActorSystem): ActorRef = {

		val downstreamTrigger: HarnessTrigger = nopTrigger
		val downstreamActions: Seq[HarnessStep] = Seq(
			receiveAndAcknowledgeLoad(outboundChannel),
			receiveAndAcknowledgeLoad(outboundChannel),
			receiveAndAcknowledgeLoad(outboundChannel)
		)

		class DownstreamHarness(configurer: HarnessConfigurer)
			extends SpecActorHarness(downstreamTrigger, downstreamActions, "downstreamHarness", gw, observer.testActor.?, configurer)
				with DirectedChannel.Sink[Material]{
			val name = uid

			override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {

			}
		}
		val downstreamConfigurer: SpecActorHarness => Configuring = host => {
			case _ =>
				outboundChannel.registerEnd(host.asInstanceOf[DirectedChannel.Sink[TotePallet]])
		}
		gw.simActorOf(Props(new DownstreamHarness(downstreamConfigurer)), "downstreamHarness")
	}
}