/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.equipment.lift

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.DirectedChannel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.base.Processor.{CompleteTask, ConfigureOwner, DeliverResult, ReceiveLoad, StageLoad, StartTask}
import com.saldubatech.base.{CarriagePhysics, DirectedChannel, Material}
import com.saldubatech.ddes.SimActor.Configuring
import com.saldubatech.ddes.SimActorMixIn
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.equipment.elements.XSwitchTransfer.Transfer
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.utils.Boxer._

class LiftExecutorOutboundSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import SpecActorHarness._

	def this() = this(ActorSystem("LiftSpec"))

	/**
		* The intended sequence of events after configuration is complete
		*
		* 1. Controller sends Outbound Command
		* 2. Upstream sends load Matching Command
		* >>> Controller Receives StagingComplete
		* >>> Upstream receives DoneWithLoad
		* >>> Downstream receives load -> Acknowledges
		* >>> Controller receives CommandComplete
		*/


	val material2 = Material("M2")
	def receiveAndAcknowledgeLoad(channel: DirectedChannel[Material], material: Material): HarnessStep = (host: SimActorMixIn, from: ActorRef, at: Long) => {
		case t: TransferLoad[Material] if t.channel == channel.uid && t.load.uid == material.uid =>
			host.log.info(s"${host.self.path.name} Received Transfer through ${t.channel} from $from")
			channel.end.loadReceiving(from,at).apply(t)
			channel.end.doneWithLoad(t.load, at)
	}


	val outboundChannel: DirectedChannel[Material]= DirectedChannel[Material](1,"outboundChannel")
	val inboundChannel: DirectedChannel[Material] = DirectedChannel[Material](1, "inboundChannel")
	val levelChannels: Array[(DirectedChannel[Material], DirectedChannel[Material])] =
		(1 until 5).toArray.map(idx =>
			(DirectedChannel[Material](1, s"upstream_in_$idx"),
			DirectedChannel[Material](1, s"upstream_out_$idx")))

	val material1 = Material("M1")
	val sendOutboundLoad: HarnessStep = (host: SimActorMixIn, from: ActorRef, at: Long) => {
		case "SendFirstLoad" =>
			levelChannels(3)._2.start.sendLoad(material1, at)
	}

	val physics: CarriagePhysics = CarriagePhysics(2,2,1,1,1)
	val underTest = LiftExecutor(
		"underTest",
		physics,
		inboundChannel,
		outboundChannel,
		levelChannels,
		inboundChannel.end)

	val upstreamTrigger: HarnessTrigger = nopTrigger
	val upstreamActions: Seq[HarnessStep] = Seq(
		sendOutboundLoad,
		nopStep(),
	)
	val upstreamObserver = TestProbe()
	class UpstreamHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(upstreamTrigger, upstreamActions, "upstreamHarness", gw, upstreamObserver.testActor.?, configurer)
	with DirectedChannel.Destination[Material] {
		override def onAccept(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {

		}

		override def onRestore(via: DirectedChannel.Start[Material], tick: Long): Unit = {

		}
	}
	val upstreamConfigurer: SpecActorHarness => Configuring = host => {
		case _ => levelChannels.foreach(io => {
			io._1.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]])
			io._2.registerStart(host.asInstanceOf[DirectedChannel.Destination[Material]])})
	}
	val upstreamEquipment: ActorRef = gw.simActorOf(Props(new UpstreamHarness(upstreamConfigurer)), "upstreamHarness")

	val downstreamTrigger: HarnessTrigger = nopTrigger
	val downstreamActions: Seq[HarnessStep] = Seq(
		receiveAndAcknowledgeLoad(outboundChannel, material1)
	)
	val downstreamObserver = TestProbe()
	class DownstreamHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(downstreamTrigger, downstreamActions, "downstreamHarness", gw, downstreamObserver.testActor.?, configurer) 	with DirectedChannel.Destination[Material] {
		override def onAccept(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {

		}

		override def onRestore(via: DirectedChannel.Start[Material], tick: Long): Unit = {

		}
	}
	val downstreamConfigurer: SpecActorHarness => Configuring = host => {
		case _ =>
			inboundChannel.registerStart(host.asInstanceOf[DirectedChannel.Destination[Material]])
			outboundChannel.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]])
	}
	val downstreamEquipment: ActorRef = gw.simActorOf(Props(new DownstreamHarness(downstreamConfigurer)), "downstreamHarness")

	val outboundCmd = Transfer(levelChannels(3)._2.end, outboundChannel.start)//(3, 0)
	val kickOff: HarnessTrigger = (host: SimActorMixIn, from: ActorRef, at: Long) => {
		implicit val iHost: SimActorMixIn = host
		host.log.info("Kickoff Controller, sending Outbound to underTest and triggering upstream equipment")
		outboundCmd ~> underTest now at
		"SendFirstLoad" ~> upstreamEquipment in (at -> 10)
	}
	val controllerTrigger: HarnessTrigger = kickOff
	val controllerActions: Seq[HarnessStep] = Seq(
		nopStep(), nopStep(), nopStep(), nopStep(), nopStep()
	)
	val controllerObserver = TestProbe()
	class ControllerHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(controllerTrigger, controllerActions, "controllerHarness", gw, controllerObserver.testActor.?, configurer)
	val controllerConfigurer: SpecActorHarness => Configuring = nopConfigure
	val controller: ActorRef = gw.simActorOf(Props(new ControllerHarness(controllerConfigurer)), "controllerHarness")

	gw.configure(controller, "ConfigureController")
	gw.configure(upstreamEquipment, "ConfigureUpstream")
	gw.configure(downstreamEquipment, "ConfigureDownstream")
	gw.configure(underTest, ConfigureOwner(controller))

	gw.injectInitialAction(upstreamEquipment, KickOff())
	gw.injectInitialAction(downstreamEquipment, KickOff())
	gw.injectInitialAction(controller, KickOff())

	gw.activate()


	"The Lift Executor" should {
		"transfer a load outbound" when {
			"receiving an outbound command and an upstream load" in {
				upstreamObserver.expectMsg("SendFirstLoad")
				controllerObserver.expectMsg(ReceiveLoad(levelChannels(3)._2.end, material1))
				controllerObserver.expectMsg(StartTask(outboundCmd.uid, Seq(material1)))
				controllerObserver.expectMsg(StageLoad(outboundCmd.uid, material1.?))
				downstreamObserver.expectMsgClass(classOf[TransferLoad[Material]])
				upstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(DeliverResult(outboundCmd.uid, outboundChannel.start,material1))
				controllerObserver.expectMsg(CompleteTask(outboundCmd.uid,Seq(material1),Seq(material1)))
			}
		}
	}
}
