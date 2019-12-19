/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.equipment.units.shuttle.lift

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.saldubatech.base.channels.Channel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.base.processor.Processor.{CompleteTask, ConfigureOwner, DeliverResult, ReceiveLoad, StageLoad, StartTask}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.{CarriagePhysics, Material}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.base.processor.XSwitchTransfer.Transfer
import com.saldubatech.equipment.units.shuttle.LiftExecutor
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.util.Lang._

class LiftExecutorInboundCmdDelaySpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import SpecActorHarness._

	val nopStep: HarnessStep = (host, _, at) => { case _ => host.log.info(s"Step in ${host.self.path.name} at $at")}
	val nopTrigger: HarnessTrigger = (_, _, _) => {}
	val nopConfigure: HarnessConfigurer = _ => {case _ => }

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
	def receiveAndAcknowledgeLoad(channel: DirectedChannel[Material], material: Material): HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
		case t: TransferLoad[Material] if t.channel == channel.uid && t.load.uid == material.uid =>
			host.log.info(s"${host.self.path.name} Received Transfer through ${t.channel} from $from")
			channel.end.loadReceiving(from,at).apply(t)
			channel.end.doneWithLoad(t.load, at)
	}


	val outboundChannel: DirectedChannel[Material]= DirectedChannel[Material](1,"outboundChannel")
	val inboundChannel: DirectedChannel[Material] = DirectedChannel[Material](1, "inboundChannel")
	val levelChannels: List[(DirectedChannel[Material], DirectedChannel[Material])] =
		(1 until 5).toList.map(idx =>
			(DirectedChannel[Material](1, s"upstream_in_$idx"),
			DirectedChannel[Material](1, s"upstream_out_$idx")))

	val material1 = Material("M1")

	val physics: CarriagePhysics = CarriagePhysics(2,2,1,1,1)
	val underTest = LiftExecutor(
		"underTest",
		physics,
		inboundChannel,
		outboundChannel,
		levelChannels,
		inboundChannel.end
	)

	val upstreamTrigger: HarnessTrigger = nopTrigger
	val upstreamActions: Seq[HarnessStep] = Seq(
		receiveAndAcknowledgeLoad(levelChannels(3)._1, material1)
	)
	val upstreamObserver = TestProbe()
	class UpstreamHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(upstreamTrigger, upstreamActions, "upstreamHarness", gw, upstreamObserver.testActor.?, configurer)
	with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {
		val name = uid

		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {

		}

		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {

		}
	}
	val upstreamConfigurer: SpecActorHarness => Configuring = host => {
		case _ => levelChannels.foreach(io => {
			io._1.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]])
			io._2.registerStart(host.asInstanceOf[DirectedChannel.Source[Material]])})
	}
	val upstreamEquipment: ActorRef = gw.simActorOf(Props(new UpstreamHarness(upstreamConfigurer)), "upstreamHarness")


	val sendInboundLoad: HarnessStep = (host: SimActor, from: ActorRef, at: Long) => {
		case "SendFirstLoad" =>
			inboundChannel.start.sendLoad(material1, at)
	}
	val downstreamTrigger: HarnessTrigger = nopTrigger
	val downstreamActions: Seq[HarnessStep] = Seq(
		sendInboundLoad, nopStep
	)
	val downstreamObserver = TestProbe()
	class DownstreamHarness(configurer: HarnessConfigurer)
		extends SpecActorHarness(
			downstreamTrigger,
			downstreamActions,
			"downstreamHarness",
			gw,
			downstreamObserver.testActor.?,
			configurer)
			with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {
		val name = uid

		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {}

		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {}
	}

	val downstreamConfigurer: SpecActorHarness => Configuring = host => {
		case _ =>
			inboundChannel.registerStart(host.asInstanceOf[DirectedChannel.Source[Material]])
			outboundChannel.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]])
	}
	val downstreamEquipment: ActorRef = gw.simActorOf(Props(new DownstreamHarness(downstreamConfigurer)), "downstreamHarness")

	val inboundCmd = Transfer(inboundChannel.end, levelChannels(3)._1.start, material1.uid.?)//(0, 3)
	val kickOff: HarnessTrigger = (host: SimActor, from: ActorRef, at: Long) => {
		host.log.info("Kickoff Controller, sending Outbound to underTest and triggering upstream equipment")
		implicit val iHost: SimActor = host
		inboundCmd ~> underTest in ((at, 10))
		"SendFirstLoad" ~> downstreamEquipment now at
	}
	val controllerTrigger: HarnessTrigger = kickOff
	val controllerActions: Seq[HarnessStep] = Seq(
		nopStep, nopStep, nopStep, nopStep, nopStep
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
				downstreamObserver.expectMsg("SendFirstLoad")
				controllerObserver.expectMsg(ReceiveLoad(inboundChannel.end, material1))
				controllerObserver.expectMsg(StartTask(inboundCmd.uid, Seq(material1)))
				controllerObserver.expectMsg(StageLoad(inboundCmd.uid, material1.?))
				upstreamObserver.expectMsgClass(classOf[TransferLoad[Material]])
				downstreamObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				controllerObserver.expectMsg(DeliverResult(inboundCmd.uid, levelChannels(3)._1.start,material1))
				controllerObserver.expectMsg(CompleteTask(inboundCmd.uid, Seq(material1),Seq(material1)))
			}
		}
	}
}
