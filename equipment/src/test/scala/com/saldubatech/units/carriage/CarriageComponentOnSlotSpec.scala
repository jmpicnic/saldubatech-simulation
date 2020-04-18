/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.carriage

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor._
import com.saldubatech.ddes.SimulationController.ControllerMessage
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.SlotLocator
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.concurrent.duration._

object CarriageComponentOnSlotSpec {
	trait MockSignal extends ChannelConnections.DummySourceMessageType with ChannelConnections.DummySinkMessageType
	case class Configure(loc: Int, inventory: Map[SlotLocator, MaterialLoad]) extends MockSignal

	trait MockNotification
	case class Notify(msg: String) extends MockNotification
	case class CompletedConfiguration(self: Processor.Ref) extends MockNotification

	case class ELoad(loc: SlotLocator) extends MockSignal
	case class EUnload(loc: SlotLocator) extends MockSignal
	case class EInduct(from: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], at: SlotLocator) extends MockSignal
	case class EDischarge(to: Channel.Start[MaterialLoad, ChannelConnections.DummySourceMessageType], at: SlotLocator) extends MockSignal


	case class Load(override val loc: SlotLocator) extends CarriageComponent.LoadCmd(loc) with MockSignal
	case class Unload(override val loc: SlotLocator) extends CarriageComponent.UnloadCmd(loc) with MockSignal
	case class Induct(override val from: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], override val at: SlotLocator) extends CarriageComponent.InductCmd(from, at) with MockSignal
	case class Discharge(override val to: Channel.Start[MaterialLoad, ChannelConnections.DummySourceMessageType], override val at: SlotLocator) extends CarriageComponent.DischargeCmd(to, at) with MockSignal
	class MOCK_HOST(monitor: ActorRef[MockNotification]) extends CarriageComponent.Host[ChannelConnections.DummySourceMessageType, ChannelConnections.DummySinkMessageType] {
		override type HOST_SIGNAL = MockSignal

		override type LOAD_SIGNAL = Load
		override def loader(loc: SlotLocator) = Load(loc)
		override type UNLOAD_SIGNAL = Unload
		override def unloader(loc: SlotLocator) = Unload(loc)
		override type INDUCT_SIGNAL = Induct
		override def inducter(from: Channel.End[MaterialLoad, ChannelConnections.DummySinkMessageType], at: SlotLocator) = Induct(from, at)
		override type DISCHARGE_SIGNAL = Discharge
		override def discharger(to: Channel.Start[MaterialLoad, ChannelConnections.DummySourceMessageType], at: SlotLocator) = Discharge(to, at)

	}


	class Harness(monitor: ActorRef[MockNotification], physics: CarriageTravel) extends LogEnabled {
		val host = new MOCK_HOST(monitor)
		val carriage =
			new CarriageComponent[ChannelConnections.DummySourceMessageType, ChannelConnections.DummySinkMessageType, MOCK_HOST](physics, host)

		private val loadingProcessing: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER] = {
			ctx => {
				case CarriageComponent.OperationOutcome.InTransit =>
					monitor ! Notify(s"In Transit to Load at ${ctx.now}")
					INTRANSIT_LOADING
				case CarriageComponent.LoadOperationOutcome.Loaded =>
					monitor ! Notify(s"Completed Loading at ${ctx.now}")
					EFULL
				case CarriageComponent.LoadOperationOutcome.ErrorTargetEmpty =>
					monitor ! Notify(s"Error Loading: Target Empty at ${ctx.now}")
					EIDLE
				case CarriageComponent.LoadOperationOutcome.ErrorTrayFull =>
					monitor ! Notify(s"Error Loading: Tray Full at ${ctx.now}")
					EFULL
			}
		}

		private val unloadingProcessing: host.CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, host.RUNNER] = {
			ctx => {
				case CarriageComponent.OperationOutcome.InTransit =>
					monitor ! Notify(s"In Transit to Unload at ${ctx.now}")
					INTRANSIT_UNLOADING
				case CarriageComponent.UnloadOperationOutcome.Unloaded =>
					monitor ! Notify(s"Completed Unloading at ${ctx.now}")
					EIDLE
				case CarriageComponent.UnloadOperationOutcome.ErrorTargetFull =>
					monitor ! Notify(s"Error Loading: Target Full at ${ctx.now}")
					EFULL
				case CarriageComponent.UnloadOperationOutcome.ErrorTrayEmpty =>
					monitor ! Notify(s"Error Loading: Tray Empty at ${ctx.now}")
					EIDLE
			}
		}

		val INTRANSIT_LOADING: host.RUNNER = carriage.LOADING(loadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					Processor.DomainRun.same
			}
		}
		val INTRANSIT_UNLOADING: host.RUNNER = carriage.UNLOADING(unloadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					Processor.DomainRun.same
			}
		}
		val INTRANSIT_INDUCTING: host.RUNNER = carriage.INDUCTING(loadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					Processor.DomainRun.same
			}
		}
		val INTRANSIT_DISCHARGING: host.RUNNER = carriage.DISCHARGING(unloadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					Processor.DomainRun.same
			}
		}

		val EIDLE: host.RUNNER = {
			implicit ctx: host.CTX => {
				case cmd: ELoad =>
					carriage.loadFrom(cmd.loc)
					INTRANSIT_LOADING
				case cmd: EInduct =>
					carriage.inductFrom(cmd.from, cmd.at)
					INTRANSIT_INDUCTING
				case other =>
					monitor ! Notify(s"Rejecting Command $other")
					Processor.DomainRun.same
			}
		}

		val EFULL: host.RUNNER = {
			implicit ctx: host.CTX => {
				case cmd: EUnload =>
					carriage.unloadTo(cmd.loc)
					INTRANSIT_UNLOADING
				case cmd: EDischarge =>
					carriage.dischargeTo(cmd.to, cmd.at)
					INTRANSIT_DISCHARGING
				case other =>
					monitor ! Notify(s"Rejecting Command $other")
					Processor.DomainRun.same
			}
		}

		def configurer: Processor.DomainConfigure[MockSignal] = new Processor.DomainConfigure[MockSignal] {
			override def configure(config: MockSignal)(implicit ctx: Processor.SignallingContext[MockSignal]): Processor.DomainRun[MockSignal] = config match {
				case Configure(loc, inventory) =>
					carriage.atLocation(loc).withInventory(inventory)
					ctx.configureContext.reply(CompletedConfiguration(ctx.aCtx.self))
					ctx.aCtx.log.debug(s"Completed configuration and notifiying ${ctx.from}")
					EIDLE
				case other => throw new IllegalArgumentException(s"Unknown Signal; $other")
			}
		}
	}

}

class CarriageComponentOnSlotSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {
	import CarriageComponentOnSlotSpec._

	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	"A Carriage" when {
		val globalClock = testKit.spawn(Clock())

		val testController = testKit.createTestProbe[ControllerMessage]

		val harnessObserver = testKit.createTestProbe[(Tick,MockNotification)]

		val shuttleHarnessProcessor = new ProcessorSink[MockNotification](harnessObserver.ref, globalClock)
		val shuttleHarness = testKit.spawn(shuttleHarnessProcessor.init)

		val harnessMonitor = testKit.createTestProbe[MockNotification]

//		val mockProcessorReceiver = testKit.createTestProbe[ProcessorMessage]
		val testActor = testKit.createTestProbe[ProcessorMessage]

		val physics = new CarriageTravel(2, 6, 4, 8, 8)
		val harness = new Harness(harnessMonitor.ref, physics)
		val carriageProcessor = new Processor[MockSignal]("underTest", globalClock, testController.ref, harness.configurer)
		val underTest = testKit.spawn(carriageProcessor.init, "undertest")

		val loadProbe = new MaterialLoad("loadProbe")
		val loadProbe2 = new MaterialLoad("loadProbe2")
		val locAt0 = OnRight(0)
		val locAt7 = OnRight(7)
		val locAt5 = OnLeft(5)
		val locAt10 = OnRight(10)


		"A. Register Itself for configuration" should {
			//			globalClock ! RegisterMonitor(testController.ref)
			//			testController.expectMessage(RegisteredClockMonitors(1))

			//			testController.expectMessage(StartedOn(0L))


			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor(underTest))
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				underTest ! ConfigurationCommand(shuttleHarness, 0L, Configure(locAt0.idx, Map(locAt0 -> loadProbe, locAt5 -> loadProbe2)))
				globalClock ! StartTime(0L)
				testController.expectMessage(CompleteConfiguration(underTest))
				harnessObserver.expectMessage((0L, CompletedConfiguration(underTest)))
			}
			"A03 Load the tray when empty with the acquire delay and reject another command in between" in {
				val loadCommand = ELoad(locAt0)
				underTest ! ProcessCommand(shuttleHarness, 2L, loadCommand)
				underTest ! ProcessCommand(shuttleHarness, 3L, loadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Reject Signal ELoad(OnRight(0)) while in Transit"))
				harnessMonitor.expectMessage(500 millis, Notify("Completed Loading at 10"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A04 Reject a command to load again" in {
				val loadCommand = ELoad(locAt0)
				underTest ! ProcessCommand(shuttleHarness, 11L, loadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Rejecting Command ELoad(OnRight(0))"))
				harnessMonitor.expectNoMessage(500 millis)
 			}
			"A06 Reject an unload request for a full location"  in {
				val unloadCommand = EUnload(locAt5)
				underTest ! ProcessCommand(shuttleHarness, 14L, unloadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Error Loading: Target Full at 29"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A07 Unload the tray with the original content" in {
				val unloadCommand = EUnload(locAt10)
				underTest ! ProcessCommand(shuttleHarness, 15L, unloadCommand)
				harnessMonitor.expectMessage(500 millis,Notify("Completed Unloading at 30"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A08 Reject a command to unload again" in {
				val unloadCommand = EUnload(locAt10)
				underTest ! ProcessCommand(shuttleHarness, 24L, unloadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Rejecting Command EUnload(OnRight(10))"))
				harnessMonitor.expectNoMessage(500 millis)
			}
		}
	}
}
