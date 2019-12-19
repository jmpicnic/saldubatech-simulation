/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
package com.saldubatech.model.builders

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging.StandardOutLogger
import akka.testkit.TestProbe
import com.saldubatech.base.Aisle.{LevelLocator, Locator}
import com.saldubatech.base.processor.Processor._
import com.saldubatech.base.channels.Channel.{AcknowledgeLoad, TransferLoad}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.base.{Aisle, CarriagePhysics, Material}
import com.saldubatech.ddes.SimActor
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimDSL._
import com.saldubatech.equipment.composites.StorageModule.{Groom, Inbound, InitializeInventory, Outbound}
import com.saldubatech.model.builders.Builder.Registry
import com.saldubatech.model.builders.ChannelBuilder.IOChannels
import com.saldubatech.model.configuration.Layout.TransportLink
import com.saldubatech.model.configuration.ShuttleStorage.{Lift, ShuttleAisle, ShuttleLevel}
import com.saldubatech.test.utils.{BaseActorSpec, SpecActorHarness}
import com.saldubatech.util.Lang._

class ControlledShuttleAisleBuilderSpec(_system: ActorSystem) extends BaseActorSpec(_system) {
	import SpecActorHarness._

	def this() = this(ActorSystem("ShuttleSpec"))

	implicit val elementRegistry = new Registry[ActorRef]()
	implicit val channelRegistry = new Registry[DirectedChannel[Material]]()


  override def afterAll: Unit = {
//	  gw.shutdown()
  }

	val logger = new StandardOutLogger()
	val shuttlePhysics = CarriagePhysics(2,2,10,1,1)
	val liftPhysics: CarriagePhysics = CarriagePhysics(2,2,1,1,1)


	val materialL2_3 = Material("Left-2-3")
	val materialR2_6 = Material("Right-2-1")
	val materialL4_5 = Material("Left-4-5")
	val materialR4_2 = Material("Right-4-2")

	val initialContentsFlat: Map[Locator, Material] = Map(
		Locator(2, Aisle.Side.LEFT, 3) -> materialL2_3,
		Locator(2, Aisle.Side.RIGHT, 6) -> materialR2_6,
		Locator(4, Aisle.Side.LEFT, 5) -> materialL4_5,
		Locator(4, Aisle.Side.RIGHT, 2) -> materialR4_2
	)

	def shuttleConfigurationGenerator(idx: Int): ShuttleLevel = {
		val name = f"LevelBuilder_$idx%2d"
		val inboundLink = TransportLink(f"underTest_Lift", "underTest_$idx%2d", 2)
		val outboundLink = TransportLink("underTest_$idx%2d", f"underTest_Lift", 2)
		val initialPosition = LevelLocator(Aisle.Side.LEFT, 0)
		ShuttleLevel(name, 7, shuttlePhysics, initialPosition, inboundLink, outboundLink)
	}

	val conf = ShuttleAisle("aisleBuilder",
		(0 until 6).map(
			idx => shuttleConfigurationGenerator(idx)).toList,
		Lift("LiftBuilder", liftPhysics, 0) // ioLevel
	)
	val underTestBuilder = ControlledShuttleAisleBuilder(conf)

	val IOChannels(inboundChannel, outboundChannel) =
		underTestBuilder.build("underTest",
			TransportLink("Outside", "underTest_Lift", 2),
			TransportLink("underTest_Lift", "Outside", 2)).!

	val underTest = underTestBuilder.findElement(underTestBuilder.lastBuilt.head).!

	// 1. Retrieve Command --> Load arrives downstream on the right channel
	val retrieveCommand1 = Outbound(Aisle.Locator(2, Aisle.Side.LEFT, 3))
	val controllerTrigger: HarnessTrigger =
		(host: SimActor, from: ActorRef, at: Long) => {implicit val h: SimActor = host; retrieveCommand1 ~> underTest now at}
	val equipmentTrigger: HarnessTrigger = (host: SimActor, from: ActorRef, at: Long) => {}

	// 2. Complete Command is received
	val stageOutbound: HarnessStep = (host, from, at) => {
		case StageLoad(uid,load1) if uid == retrieveCommand1.uid  && load1.! == materialL2_3 =>
			host.log.info(">>>>>> Received CompleteStaging in Step 2 <<<<<<<<<<<<<<<<")
	}
	val completeOutboundCmd: HarnessStep = (host, from, at) => {
		case CompleteTask(uid,loads1, loads2)
			if uid == retrieveCommand1.uid  && loads2.head == materialL2_3 && loads1.isEmpty =>
			host.log.info(">>>>>> Received CompleteProcessing in Step 2 <<<<<<<<<<<<<<<<")
	}

	val retrievedLoadConfirm = s"Received Load Retrieved: ${materialL2_3.uid}"
	var firstResource: String = ""
	val receiveFirstLoad: HarnessStep = (host, from, at) => {
		case cmd: TransferLoad[Material] =>
			implicit val h: SimActor = host
			firstResource = cmd.resource.!
			outboundChannel.end.doLoadReceiving(from, at, cmd.load, cmd.resource)
			host.log.info(s"Acknowledging Load ${cmd.load} through ${cmd.channel}")
			outboundChannel.end.doneWithLoad(cmd.load, at)
			retrievedLoadConfirm ~> controller now at
	}
	// 3. Store Command --> Nothing happens
	val materialR5 = Material("Right-5")
	val storeCommand1 = Inbound(Aisle.Locator(3, Aisle.Side.RIGHT, 5), materialR5.uid.?)
	val sendFirstLoad = "SendFirstLoad"
	val confirmFromDownstream: HarnessStep = (host, from, at) => {
		case msg if msg == retrievedLoadConfirm && from == equipmentHarness =>
			implicit val h: SimActor = host
			host.log.info(s"Received completion of first retrieval load, sending Store Command and Its load")
			storeCommand1 ~> underTest now at
			sendFirstLoad ~> equipmentHarness in (at, 10)
	}//{host send KickOff() _to equipmentHarness now at}


	// 4. Load arrives 10 ticks later --> Load is transferred to destination location
	val controllerSendFirstLoad: HarnessStep = (host, from, at) => {
		case msg if msg == sendFirstLoad =>
			host.log.info(s"Send load $materialR5")
			assert(inboundChannel.start.sendLoad(materialR5, at), "Should be able to send load to Left IO")
	}

	//ConfirmDirectionSwitch(L1)
	// 5. complete command message is received
	val sendSecondLoad = "SendSecondLoad"
	val materialL2 = Material("Left-2")
	val storeCommand2 = Inbound(Aisle.Locator(3, Aisle.Side.LEFT, 2), materialL2.uid.?)
	val stageStoreCmd: HarnessStep = (host, from, at) => {
		case StageLoad(cmd, load) if cmd == storeCommand1.uid && load.! == materialR5 =>
	}
	val completeStoreCmd: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, inputs, outputs)
			if cmdUid == storeCommand1.uid && inputs.head == materialR5 && outputs.isEmpty =>
			host.log.info(s">>> Received Completion of StoreCmd1")
			implicit val h: SimActor = host
			sendSecondLoad ~> equipmentHarness in (at, 10)
			storeCommand2 ~> underTest in (at, 30)
	}
	// 6. Activate EquipmentHarness -> Load Arrives --> Nothing happens
	// 7. Store Command --> Load is transferred to destination location
	val controllerSendSecondLoad: HarnessStep = (host, from, at) => {
		case msg if msg == sendSecondLoad =>
			implicit val h: SimActor = host
			host.log.info(s"Sending Second Load $materialL2")
			host.log.info(s"Send load $materialL2")
			assert(inboundChannel.start.sendLoad(materialL2, at),
				"Should be able to send after the direction switch")
	}

	// 8. Complete command message is received
	// 9. Groom Command --> Load is transferred
	val groomCommand = Groom(Aisle.Locator(3, Aisle.Side.LEFT, 2), Aisle.Locator(5, Aisle.Side.LEFT, 5))
	val store2Stage: HarnessStep = (host, from, at) => {
		case StageLoad(cmdUid, load) if cmdUid == storeCommand2.uid && load.! == materialL2 =>

	}
	val store2Complete: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, inputs, outputs)
			if cmdUid == storeCommand2.uid && inputs.head == materialL2 && outputs.isEmpty =>
			implicit val h: SimActor = host
			host.log.info(s"Starting Groom Command")
			groomCommand ~> underTest now at
	}
	// 10. Complete Command is received
	// 11. Retrieve Command from "groom" destination

	val groomStage: HarnessStep = (host, from, at) => {
		case StageLoad(cmdUid, load) if cmdUid == groomCommand.uid && load.! == materialL2 =>

	}

	val finalRetrieveCmd = Outbound(Aisle.Locator(5, Aisle.Side.LEFT, 5))
	val groomComplete: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, inputs, outputs)
			if cmdUid == groomCommand.uid && inputs.head ==  materialL2 && outputs.isEmpty =>
			implicit val h: SimActor = host
			finalRetrieveCmd ~> underTest now at
	}
	// 12. Load arrives downstream on the correct channel
	val receivedSecondLoad: HarnessStep = (host, from, at) => {
		case tl: TransferLoad[Material] =>
			implicit val h: SimActor = host
			AcknowledgeLoad[Material](tl.channel, tl.load, tl.resource) ~> underTest now at
			outboundChannel.end.doLoadReceiving(from, at, tl.load, tl.resource)
	}
	// 13. Complete Command is received.
	val stageFinalRetrieve: HarnessStep = (host, from, at) => {
		case StageLoad(cmdUid, load) if cmdUid == finalRetrieveCmd.uid && load.! == materialL2 =>
	}
	val completeFinalRetrieve: HarnessStep = (host, from, at) => {
		case CompleteTask(cmdUid, inputs, outputs)
			if cmdUid == finalRetrieveCmd.uid && inputs.isEmpty && outputs.head == materialL2 =>
			testActor ! s"Completed test with cmd ${finalRetrieveCmd.uid}"
	}


	val equipmentObserver = TestProbe()

	val discardAcknowledge: HarnessStep = (host,from,at) => { case c: Any => host.log.info(s"Step 1_1 is: $c")}
	val equipmentActions: Seq[(SimActor, ActorRef, Long) => Processing] = Seq(
		receiveFirstLoad, // 0
		controllerSendFirstLoad, // 1
		discardAcknowledge, // 2
		controllerSendSecondLoad, // 3
		nopStep("Acknowledge from Lift"),//4
		receivedSecondLoad // 5
	)

	class EquipmentHarness(configurer: SpecActorHarness => Configuring)
		extends SpecActorHarness(equipmentTrigger, equipmentActions, "equipmentHarness", gw, equipmentObserver.testActor.?, configurer)
			with DirectedChannel.Destination[Material]
				with DirectedChannel.Source[Material] {
		val name = uid
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



	val controllerActions: Seq[(SimActor, ActorRef, Long) => Processing] = Seq(
		nopStep("Start Outbound"),//0
		stageOutbound,//1
		nopStep("Deliver Outbound"),//2
		completeOutboundCmd,//3
		confirmFromDownstream,//4
		nopStep("Store ReceiveLoad"),//5
		nopStep("Store Start"),//6
		stageStoreCmd,//7
		completeStoreCmd,//8
		nopStep("Store 2 Start"),//9
		nopStep("Store 1 Receive Load"),
		store2Stage,//10
		store2Complete,//13
//		nopStep("Store 2 Deliver"),//12
		nopStep("Groom Start"),//14
		groomStage,//15
		groomComplete,//17
		nopStep("Final Retrieve Start"),//18
		stageFinalRetrieve,//19
		nopStep("Deliver final retrieve"),//20
		completeFinalRetrieve//21
	)

	val controller:ActorRef = SpecActorHarness.simActor(controllerTrigger, controllerActions, "controller", gw, testActor.?)

	// Configuration
	gw.configure(underTest, ConfigureOwner(controller), InitializeInventory(initialContentsFlat))
	gw.configure(equipmentHarness, "Dummy Configure")
	gw.configure(controller, "Dummy Configure")

	gw.injectInitialAction(equipmentHarness, KickOff())
	gw.injectInitialAction(controller, KickOff())

	gw.activate()




	"The Aisle" should {
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
				expectMsg(StartTask(retrieveCommand1.uid, Seq()))
				expectMsg(StageLoad(retrieveCommand1.uid, materialL2_3.?))
				expectMsg(DeliverResult(retrieveCommand1.uid, outboundChannel.start, materialL2_3))
				expectMsgAllOf(CompleteTask(retrieveCommand1.uid, Seq(),Seq(materialL2_3)), retrievedLoadConfirm)
				equipmentObserver.expectMsg(sendFirstLoad)
				expectMsg(ReceiveLoad(inboundChannel.end, materialR5))
				expectMsg(StartTask(storeCommand1.uid, Seq(materialR5)))
				expectMsg(StageLoad(storeCommand1.uid, materialR5.?))
				equipmentObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				expectMsg(CompleteTask(storeCommand1.uid, Seq(materialR5), Seq()))
				equipmentObserver.expectMsg(sendSecondLoad)
				expectMsg(ReceiveLoad(inboundChannel.end,materialL2))
				expectMsg(StartTask(storeCommand2.uid,Seq(materialL2)))
				expectMsg(StageLoad(storeCommand2.uid, materialL2.?))
				equipmentObserver.expectMsgClass(classOf[AcknowledgeLoad[Material]])
				expectMsg(CompleteTask(storeCommand2.uid, Seq(materialL2), Seq()))
				expectMsg(StartTask(groomCommand.uid, Seq()))
				expectMsg(StageLoad(groomCommand.uid, materialL2.?))
				expectMsg(CompleteTask(groomCommand.uid, Seq(materialL2), Seq()))
				expectMsg(StartTask(finalRetrieveCmd.uid, Seq()))
				expectMsg(StageLoad(finalRetrieveCmd.uid, materialL2.?))
				expectMsg(DeliverResult(finalRetrieveCmd.uid, outboundChannel.start, materialL2))
				expectMsg( s"Completed test with cmd ${finalRetrieveCmd.uid}")
				expectMsg(CompleteTask(finalRetrieveCmd.uid, Seq(), Seq(materialL2)))
				equipmentObserver.expectMsgClass(classOf[TransferLoad[Material]])
			}
		}
	}

}
