/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.v1.model.builders

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging.StandardOutLogger
import akka.testkit.TestProbe
import com.saldubatech.v1.base.processor.Processor._
import com.saldubatech.v1.base.channels.Channel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.v1.base.{Aisle, CarriagePhysics, Material}
import com.saldubatech.v1.ddes.SimActor
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimDSL._
import com.saldubatech.v1.equipment.units.shuttle.ShuttleLevelExecutor.{Groom, Inbound, InitializeInventory, Outbound}
import com.saldubatech.v1.model.builders.Builder.Registry
import com.saldubatech.v1.model.configuration.Layout.TransportLink
import com.saldubatech.v1.model.configuration.ShuttleStorage.ShuttleLevel
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.util.Lang._


class ShuttleLevelBuilderSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import SpecActorHarness._

	def this() = this(ActorSystem("ShuttleSpec"))

	implicit val elementRegistry = new Registry[ActorRef]()
	implicit val channelRegistry = new Registry[DirectedChannel[Material]]()


  override def afterAll: Unit = {
//	  gw.shutdown()
  }

	//val logger = new StandardOutLogger()
	val physics = CarriagePhysics(2,2,10,1,1)


	val materialL3 = Material("Left-3")
	val materialR6 = Material("Right-1")


	val configuration = ShuttleLevel("underTest",7,physics, Aisle.LevelLocator(Aisle.Side.LEFT, 0),
		TransportLink("OutDummy", "InDummy", 2), TransportLink("InDummy", "OutDummy", 2))

	val builder = ShuttleLevelBuilder(configuration)
	val iochannels  = builder.build("underTest")
	val inboundChannel = iochannels.!.in
	val outboundChannel = iochannels.!.out
	specLog.debug(s"Retrieve Undertest from ${builder.lastBuilt}")
	val underTest = builder.findElement(builder.lastBuilt.head).!

	// 1. Retrieve Command --> Load arrives downstream on the right channel
	val retrieveCommand1 = Outbound(Aisle.LevelLocator(Aisle.Side.LEFT, 3))
	val controllerTrigger: HarnessTrigger =
		(host: SimActor, from: ActorRef, at: Long) => {implicit val h: SimActor = host; retrieveCommand1 ~> underTest now at}
	val equipmentTrigger: HarnessTrigger = (host: SimActor, from: ActorRef, at: Long) => {}

	// 2. Complete Command is received
	val controllerStartProcessing000: HarnessStep = (host, from, at) => {
		case StartTask(uid,materials) if uid == retrieveCommand1.uid  && materials.isEmpty =>
			host.log.info(">>>>>> Received StartProcessing in Step 000 <<<<<<<<<<<<<<<<")
	}
	val controllerStageLoad00: HarnessStep = (host, from, at) => {
		case StageLoad(uid,load1) if uid == retrieveCommand1.uid  && load1.! == materialL3 =>
			host.log.info(">>>>>> Received CompleteStaging in Step 00 <<<<<<<<<<<<<<<<")
	}
	val controllerCompleteTask0: HarnessStep = (host, from, at) => {
		case CompleteTask(uid,load1, load2)
			if uid == retrieveCommand1.uid && load1.isEmpty && load2.head == materialL3 && load1.isEmpty =>
			host.log.info(">>>>>> Received CompleteProcessing in Step 2 <<<<<<<<<<<<<<<<")
	}

	val retrievedLoadConfirm = s"Received Load Retrieved: ${materialL3.uid}"
	var firstResource: String = ""
	val equipmentStep0: HarnessStep = (host, from, at) => {
		case TransferLoad(channel, load, resource) =>
			implicit val h: SimActor = host
			firstResource = resource.!
			host.log.info(s"Acknowledging Load $load")
			AcknowledgeLoad(channel, load, resource) ~> underTest now at
			retrievedLoadConfirm ~> controller now at
	}
	// 3. Store Command --> Nothing happens
	val storeCommand1 = Inbound(Aisle.LevelLocator(Aisle.Side.RIGHT, 5))
	val sendFirstLoad = "SendFirstLoad"
	val controllerReceivedLoadConfirm1: HarnessStep = (host, from, at) => {
		case msg if msg == retrievedLoadConfirm && from == equipmentHarness =>
			implicit val h: SimActor = host
			host.log.info(s"Received completion of first retrieval load, sending Store Command and Its load")
			storeCommand1 ~> underTest now at
			sendFirstLoad ~> equipmentHarness in (at, 10)
	}//{host send KickOff() _to equipmentHarness now at}


	// 4. Load arrives 10 ticks later --> Load is transferred to destination location
	val materialR5 = Material("Right-5")
	val equipmentStep1: HarnessStep = (host, from, at) => {
		case msg if msg == sendFirstLoad =>
			host.log.info(s"Send load $materialR5")
			assert(inboundChannel.start.sendLoad(materialR5, at), "Should be able to send load to Left IO")
	}

	//ConfirmDirectionSwitch(L1)
	// 5. complete command message is received
	val controllerStageLoadStore1: HarnessStep = (host, from, at) => {
		case StageLoad(cmd, load) if cmd == storeCommand1.uid && load.! == materialR5 =>
	}

	val sendSecondLoad = "SendSecondLoad"
	val storeCommand2 = Inbound(Aisle.LevelLocator(Aisle.Side.LEFT, 2))
	val controllerCompleteStore1: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, input, output)
			if cmdUid == storeCommand1.uid && input.head == materialR5 && output.isEmpty =>
			host.log.info(s">>> Received Completion of StoreCmd1")
			implicit val h: SimActor = host
			sendSecondLoad ~> equipmentHarness in (at, 10)
			storeCommand2 ~> underTest in (at, 30)
	}
	// 6. Activate EquipmentHarness -> Load Arrives --> Nothing happens
	// 7. Store Command --> Load is transferred to destination location
	val materialL2 = Material("Left-2")
	val equipmentStep2: HarnessStep = (host, from, at) => {
		case msg if msg == sendSecondLoad =>
			implicit val h: SimActor = host
			host.log.info(s"Sending Second Load $materialL2")
			host.log.info(s"Send load $materialL2")
			assert(inboundChannel.start.sendLoad(materialL2, at),
				"Should be able to send after the direction switch")
	}

	// 8. Complete command message is received
	// 9. Groom Command --> Load is transferred
	val groomCommand = Groom(Aisle.LevelLocator(Aisle.Side.LEFT, 2), Aisle.LevelLocator(Aisle.Side.LEFT, 5))
	val discardStageLoadStore2: HarnessStep = (host, from, at) => {
		case StageLoad(cmdUid, load) if cmdUid == storeCommand2.uid && load.head == materialL2 =>
	}
	val completeStore2: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, input, output)
			if cmdUid == storeCommand2.uid && input.head == materialL2 && output.isEmpty =>
			implicit val h: SimActor = host
			groomCommand ~> underTest now at
	}
	// 10. Complete Command is received
	// 11. Retrieve Command from "groom" destination


	val completeGroom: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, input, output)
			if cmdUid == groomCommand.uid && input.isEmpty && output.isEmpty =>
			implicit val h: SimActor = host
			finalRetrieveCmd ~> underTest now at
	}
	val finalRetrieveCmd = Outbound(Aisle.LevelLocator(Aisle.Side.LEFT, 5))
	// 12. Load arrives downstream on the correct channel
	val equipmentTransferLoad: HarnessStep = (host, from, at) => {
		//TransferLoad[L <: Identification](channel: String, load: L, resource: String)
		case tl: TransferLoad[Material] =>
			implicit val h: SimActor = host
			AcknowledgeLoad[Material](tl.channel, tl.load, tl.resource) ~> underTest now at
			//equipmentObserver.testActor ! s"Received ${tl.load.uid} on ${tl.channel}"
	}
	// 13. Complete Command is received.
	val stageLoadFinalRetrieval: HarnessStep = (host, from, at) => {
		case StageLoad(cmdUid, load) if cmdUid == finalRetrieveCmd.uid && load.! == materialL2 =>
	}
	val deliverResultFinalRetrieval: HarnessStep = (host, form, at) => {
		case DeliverResult(cmd, via,material)
			if cmd == finalRetrieveCmd.uid && via == outboundChannel.start && material == materialL2 =>
	}
	val completeFinalRetrieval: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, input, output)
			if cmdUid == finalRetrieveCmd.uid && input.isEmpty && output.head == materialL2 =>
			testActor ! s"Completed test with cmd ${finalRetrieveCmd.uid}"
	}

	val equipmentObserver = TestProbe()

	val discardAcknowledge: HarnessStep = (host,from,at) => { case c: Any => host.log.info(s"Step 1_1 is: $c")}
	val equipmentActions: Seq[(SimActor, ActorRef, Long) => Processing] = Seq(
		equipmentStep0,//0
		equipmentStep1,//1
		discardAcknowledge,//2
		equipmentStep2,//3
		discardAcknowledge,//4
		equipmentTransferLoad//5
	)

	class EquipmentHarness(configurer: SpecActorHarness => Configuring)
		extends SpecActorHarness(equipmentTrigger, equipmentActions, "equipmentHarness", gw, equipmentObserver.testActor.?, configurer)
			with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {
		val name = "equipmentHarness"
		override def receiveMaterial(via: DirectedChannel.End[Material], load: Material, tick: Long): Unit = {
			log.debug(s"Processing OnAccept with $load")
//			equipmentObserver.testActor ! s"Received Load ${load.uid}"
		}

		override def restoreChannelCapacity(via: DirectedChannel.Start[Material], tick: Long): Unit = {
			//left1.rightEndpoint.sendLoad(materialR5, tick)
//			equipmentObserver.testActor ! s"Restored channel: ${via.toString}"
		}
	}

	val equipmentConfigurer: SpecActorHarness => Configuring = host => {
		case _ =>
			inboundChannel.registerStart(host.asInstanceOf[DirectedChannel.Source[Material]])
			outboundChannel.registerEnd(host.asInstanceOf[DirectedChannel.Destination[Material]])
	}

	val equipmentHarness:ActorRef = gw.simActorOf(Props(new EquipmentHarness(equipmentConfigurer)), "equipmentHarness")

	val eitherOrEndOfRetrieveLoad1: HarnessStep = (host, from, at) =>
		controllerReceivedLoadConfirm1(host,from,at) orElse
			controllerCompleteTask0(host, from, at) orElse
			nopStep("Deliver Outbound")(host, from, at)


	val controllerActions: Seq[HarnessStep] = Seq(
		controllerStartProcessing000,//0
		controllerStageLoad00,//1
		eitherOrEndOfRetrieveLoad1,//2
		eitherOrEndOfRetrieveLoad1,//3
		eitherOrEndOfRetrieveLoad1,//4
		nopStep("Expect Receive Load R5"),//5
		nopStep("StartProcessing Inbound R5"),//6
		controllerStageLoadStore1,//7
		controllerCompleteStore1,//8
		nopStep("Receive Load Store 2"),//9
		nopStep("StartProcessing Store 2"),//10
		nopStep("Stage Store 2"),//11
		//discardStageLoadStore2,//11
		completeStore2,//12
		nopStep("Start Groom"),//13
		nopStep("Stage Groom"),//14
		completeGroom,//15
		nopStep("Start Final Retrieval"),//16
		stageLoadFinalRetrieval,//17
		deliverResultFinalRetrieval,//18
		completeFinalRetrieval,//19
	)

	val controller:ActorRef = SpecActorHarness.simActor(controllerTrigger, controllerActions, "controller", gw, testActor.?)

	// Configuration
	gw.configure(underTest, ConfigureOwner(controller), InitializeInventory(Map(
		Aisle.LevelLocator(Aisle.Side.LEFT, 3) -> materialL3,
		Aisle.LevelLocator(Aisle.Side.RIGHT, 6) -> materialR6
	)))
	gw.configure(equipmentHarness, "Dummy Configure")
	gw.configure(controller, "Dummy Configure")

	gw.injectInitialAction(equipmentHarness, KickOff())
	gw.injectInitialAction(controller, KickOff())

	gw.activate()




	"The shuttle" should {
		// 1. Retrieve Command --> Load arrives downstream on the right channel
		// 2. Complete Command is received
		// 3. Store Command --> Nothing happens
		// 4. Load arrives 10 ticks later --> Load is transferred to destination location
		// 5. complete command message is received
		// 6. Activate EquipmentHarness -> Load Arrives --> Nothing happens
		// 7. Store Command --> Load is transferred to destination location
		// 8. Complete command message is received
		// 9. Groom Command --> Load is transferred
		// 10. Complete Command is received
		// 11. Retrieve Command from "groom" destination
		// 12. Load arrives downstream on the correct channel
		// 13. Complete Command is received.
		"1. Retrieve a load from a prepopulated location to an io position" when {
			"it receives a Retrieval Command" in {
				equipmentObserver.expectMsgClass(classOf[TransferLoad[Material]])
				expectMsg(StartTask(retrieveCommand1.uid, Seq.empty))
				expectMsg(StageLoad(retrieveCommand1.uid, materialL3.?))
				expectMsgAllOf(
					DeliverResult(retrieveCommand1.uid, outboundChannel.start, materialL3),
					retrievedLoadConfirm,
					CompleteTask(retrieveCommand1.uid, Seq(), Seq(materialL3)))
				equipmentObserver.expectMsg(sendFirstLoad)
				expectMsg(ReceiveLoad(inboundChannel.end, materialR5))
				expectMsg(StartTask(storeCommand1.uid, Seq(materialR5)))
				expectMsg(StageLoad(storeCommand1.uid, materialR5.?))
				equipmentObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				expectMsg(CompleteTask(storeCommand1.uid, Seq(materialR5), Seq()))
				equipmentObserver.expectMsg(sendSecondLoad)
				expectMsg(ReceiveLoad(inboundChannel.end, materialL2))
				expectMsg(StartTask(storeCommand2.uid, Seq(materialL2)))
				expectMsg(StageLoad(storeCommand2.uid, materialL2.?))
				equipmentObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				expectMsg(CompleteTask(storeCommand2.uid, Seq(materialL2), Seq()))
				expectMsg(StartTask(groomCommand.uid, Seq()))
				expectMsg(StageLoad(groomCommand.uid, materialL2.?))
				expectMsg(CompleteTask(groomCommand.uid, Seq(), Seq()))
				expectMsg(StartTask(finalRetrieveCmd.uid,Seq()))
				expectMsg(StageLoad(finalRetrieveCmd.uid, materialL2.?))
				expectMsg(DeliverResult(finalRetrieveCmd.uid, outboundChannel.start, materialL2))
				expectMsg( s"Completed test with cmd ${finalRetrieveCmd.uid}")
				expectMsg(CompleteTask(finalRetrieveCmd.uid, Seq(), Seq(materialL2)))
				equipmentObserver.expectMsgClass(classOf[TransferLoad[Material]])
			}
		}
	}

}
