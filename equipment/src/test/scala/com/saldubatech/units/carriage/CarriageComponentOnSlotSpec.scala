/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.carriage

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.{Clock, Processor}
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Processor._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, SimRef}
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.protocols.Equipment
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.abstractions.{CarriageUnit, EquipmentUnit, InductDischargeUnit}
import com.saldubatech.units.abstractions.InductDischargeUnit.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

import scala.concurrent.duration._

object CarriageComponentOnSlotSpec {
	case class Configure(loc: Int, inventory: Map[SlotLocator, MaterialLoad]) extends Identification.Impl() with Equipment.MockSignal

	trait MockNotification extends DomainSignal
	case class Notify(msg: String) extends Identification.Impl() with MockNotification
	case class CompletedConfiguration(self: SimRef) extends Identification.Impl() with MockNotification

	case class ELoad(loc: SlotLocator) extends Identification.Impl() with Equipment.MockSignal
	case class EUnload(loc: SlotLocator) extends Identification.Impl() with Equipment.MockSignal
	case class EInduct(from: Channel.End[MaterialLoad, Equipment.MockSignal], at: SlotLocator) extends Identification.Impl() with Equipment.MockSignal
	case class EDischarge(to: Channel.Start[MaterialLoad, Equipment.MockSignal], at: SlotLocator) extends Identification.Impl() with Equipment.MockSignal


	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with Equipment.MockSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with Equipment.MockSignal
	case class Induct(override val from: Channel.End[MaterialLoad, Equipment.MockSignal], override val at: SlotLocator) extends InductCmd(from, at) with Equipment.MockSignal
	case class Discharge(override val to: Channel.Start[MaterialLoad, Equipment.MockSignal], override val at: SlotLocator) extends DischargeCmd(to, at) with Equipment.MockSignal
	class MOCK_CarriageUnit(monitor: ActorRef[MockNotification]) extends CarriageUnit[Equipment.MockSignal] with InductDischargeUnit[Equipment.MockSignal] {
		override lazy val self: SimRef = _self
		var _self: SimRef = null
		override val name = "MockHost"

		override type LOAD_SIGNAL = Load
		override def loader(loc: SlotLocator) = Load(loc)
		override type UNLOAD_SIGNAL = Unload
		override def unloader(loc: SlotLocator) = Unload(loc)
		override type INDUCT_SIGNAL = Induct
		override def inducter(from: INDUCT, at: SlotLocator) = Induct(from, at)
		override type DISCHARGE_SIGNAL = Discharge
		override def discharger(to: DISCHARGE, at: SlotLocator) = Discharge(to, at)

		override type HOST = MOCK_CarriageUnit
		override type EXTERNAL_COMMAND = Equipment.MockSignal
		override type NOTIFICATION = Nothing

		override protected def notAcceptedNotification(cmd: EXTERNAL_COMMAND, msg: String) = throw new IllegalStateException("Should not be called")
		override protected def completedCommandNotification(cmd: EXTERNAL_COMMAND) = throw new IllegalStateException("Should not be called")

	}


	class Harness(monitor: ActorRef[MockNotification], physics: CarriageTravel) extends LogEnabled {
		val host = new MOCK_CarriageUnit(monitor)
		val carriage =
			new CarriageComponent[Equipment.MockSignal, MOCK_CarriageUnit](physics, host)

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

		def configurer: Processor.DomainConfigure[Equipment.MockSignal] = new Processor.DomainConfigure[Equipment.MockSignal] {
			override def configure(config: Equipment.MockSignal)(implicit ctx: Processor.SignallingContext[Equipment.MockSignal]): Processor.DomainRun[Equipment.MockSignal] = config match {
				case Configure(loc, inventory) =>
					host._self = ctx.aCtx.self
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
	extends AnyWordSpec
		with Matchers
		with AnyWordSpecLike
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
		val carriageProcessor = new Processor[Equipment.MockSignal]("underTest", globalClock, testController.ref, harness.configurer)
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
				var ct = 0
				harnessMonitor.fishForMessage(500 millis){
					case Notify("Reject Signal ELoad(OnRight(0)) while in Transit") =>
						ct += 1
						if(ct == 2) FishingOutcome.Complete
						else FishingOutcome.Continue
					case Notify("Completed Loading at 10") =>
						ct += 1
						if(ct == 2) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Unexpected: $other")
				}
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
