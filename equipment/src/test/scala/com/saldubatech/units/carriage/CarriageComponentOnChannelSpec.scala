/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.carriage

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import com.saldubatech.base.Identification
import com.saldubatech.ddes.AgentTemplate._
import com.saldubatech.ddes.Clock._
import com.saldubatech.ddes.Simulation.{ControllerMessage, DomainSignal, SimRef}
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{AgentTemplate, Clock}
import com.saldubatech.protocols.{Equipment, MaterialLoad}
import com.saldubatech.test.ClockEnabled
import com.saldubatech.transport.Channel
import com.saldubatech.units.abstractions.InductDischargeUnit.{DischargeCmd, InductCmd, LoadCmd, UnloadCmd}
import com.saldubatech.units.abstractions.{CarriageUnit, InductDischargeUnit}
import com.saldubatech.util.LogEnabled
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

object CarriageComponentOnChannelSpec {
	type MockSignal = Equipment.MockSignal
	case object NullConfigure extends Identification.Impl() with  MockSignal
	case class DoConfigure(loc: Int, inventory: Map[SlotLocator, MaterialLoad]) extends Identification.Impl() with  MockSignal

	trait MockNotification extends MockSignal
	case class LoadArrival(ld: MaterialLoad, at: Tick) extends Identification.Impl() with  MockNotification
	case class Notify(msg: String) extends Identification.Impl() with  MockNotification
	case class CompletedConfiguration(self: SimRef[_ <: DomainSignal]) extends Identification.Impl() with  MockNotification


	class MockChannel(delay: () => Option[Delay], delivery: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
		extends Channel[MaterialLoad, MockSignal, MockSignal](delay, delivery, cards, configuredOpenSlots, name) {
		type TransferSignal = Channel.TransferLoad[MaterialLoad] with MockSignal
		type PullSignal = Channel.PulledLoad[MaterialLoad] with MockSignal
		type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with MockSignal
		override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with MockSignal

		override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with MockSignal
		override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with MockSignal
		override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with MockSignal

		override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with MockSignal
	}

	case class TestProbeMessage(msg: String, load: MaterialLoad) extends Identification.Impl() with  MockSignal
	case object FixtureConfigure extends Identification.Impl() with  MockSignal

	trait Fixture[DomainMessage <: DomainSignal] extends LogEnabled {
		var _ref: Option[SimRef[DomainMessage]] = None
		val runner: DomainRun[DomainMessage]
	}
	class SourceFixture(ops: Channel.Ops[MaterialLoad, MockSignal, MockSignal])(testMonitor: ActorRef[String], hostTest: AnyWordSpec) extends Fixture[MockSignal] {

		lazy val source = new Channel.Source[MaterialLoad, MockSignal] {
			override lazy val ref: SimRef[MockSignal] = _ref.head

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, MockSignal], load: MaterialLoad)(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				testMonitor ! s"Received Load Acknoledgement at Channel: ${chStart.channelName} with $load"
				runner
			}
		}
		ops.registerStart(source)

		val runner: DomainRun[MockSignal] =
			ops.start.ackReceiver orElse {
				implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal] => {
					case TestProbeMessage(msg, load) =>
						log.info(s"Got Domain Message in Sender $msg")
						testMonitor ! s"FromSender: $msg"
						ops.start.send(load)
						log.info(s"Sent $load through channel ${ops.start.channelName}")
						runner
					case other =>
						log.info(s"Received Other Message at Receiver: $other")
						hostTest.fail(s"Unexpected Message $other")
				}
			}
	}


	class SinkFixture(ops: Channel.Ops[MaterialLoad, MockSignal, MockSignal])(testMonitor: ActorRef[String], hostTest: AnyWordSpec) extends Fixture[MockSignal] {
		val sink = new Channel.Sink[MaterialLoad, MockSignal] {
			override lazy val ref: SimRef[MockSignal] = _ref.head

			override def loadArrived(endpoint: Channel.End[MaterialLoad, MockSignal], load: MaterialLoad, at: Option[Int])(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				testMonitor ! s"Load $load arrived via channel ${endpoint.channelName}"
				runner
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, MockSignal], load: MaterialLoad, at: Option[Int])(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				testMonitor ! s"Load $load released on channel ${endpoint.channelName}"
				runner
			}
		}
		ops.registerEnd(sink)

		val runner: DomainRun[MockSignal] =
			ops.end.loadReceiver orElse DomainRun {
				case other =>
					log.info(s"Received Other Message at Receiver: $other")
					hostTest.fail(s"SinkFixture: ${ops.ch.name}: Unexpected Message $other")
			}
	}

	def fixtureConfigurer[DomainMessage <: DomainSignal](fixture: Fixture[DomainMessage])(monitor: ActorRef[String]): DomainConfigure[DomainMessage] =
		new DomainConfigure[DomainMessage] {
			override def configure(config: DomainMessage)(implicit ctx:  FullSignallingContext[DomainMessage, _ <: DomainSignal]): DomainRun[DomainMessage] = {
				monitor ! s"Received Configuration: $config"
				fixture._ref = Some(ctx.aCtx.self)
				fixture.runner
			}
		}



	case class ELoad(loc: SlotLocator) extends Identification.Impl() with  MockSignal
	case class EUnload(loc: SlotLocator) extends Identification.Impl() with  MockSignal
	case class EInduct(from: Channel.End[MaterialLoad, MockSignal], at: SlotLocator) extends Identification.Impl() with  MockSignal
	case class EDischarge(to: Channel.Start[MaterialLoad, MockSignal], at: SlotLocator) extends Identification.Impl() with  MockSignal


	case class Load(override val loc: SlotLocator) extends LoadCmd(loc) with MockSignal
	case class Unload(override val loc: SlotLocator) extends UnloadCmd(loc) with MockSignal
	case class Induct(override val from: Channel.End[MaterialLoad, MockSignal], override val at: SlotLocator) extends InductCmd(from, at) with MockSignal
	case class Discharge(override val to: Channel.Start[MaterialLoad, MockSignal], override val at: SlotLocator) extends DischargeCmd(to, at) with MockSignal
	class MOCK_CarriageUnit(val monitor: ActorRef[MockNotification]) extends CarriageUnit[MockSignal] with InductDischargeUnit[MockSignal]{
		override val name = "MockHOST"
		override type MANAGER_SIGNAL = MockNotification


		override type LOAD_SIGNAL = Load
		override def loader(loc: SlotLocator) = Load(loc)
		override type UNLOAD_SIGNAL = Unload
		override def unloader(loc: SlotLocator) = Unload(loc)
		override type INDUCT_SIGNAL = Induct
		override def inducter(from: Channel.End[MaterialLoad, MockSignal], at: SlotLocator) = Induct(from, at)
		override type DISCHARGE_SIGNAL = Discharge
		override def discharger(to: Channel.Start[MaterialLoad, MockSignal], at: SlotLocator) = Discharge(to, at)

		override type HOST = MOCK_CarriageUnit
		override type EXTERNAL_COMMAND = MockSignal
		override type NOTIFICATION = Nothing

		override protected def notAcceptedNotification(cmd: EXTERNAL_COMMAND, msg: String) = throw new IllegalStateException("Should not be called")
		override protected def completedCommandNotification(cmd: EXTERNAL_COMMAND) = throw new IllegalStateException("Should not be called")
	}


	class Harness(monitor: ActorRef[MockNotification], physics: CarriageTravel, inbound: Channel.Ops[MaterialLoad, MockSignal, MockSignal],
	              outbound: Channel.Ops[MaterialLoad, MockSignal, MockSignal]) extends LogEnabled {
		val host = new MOCK_CarriageUnit(monitor)
		val carriage = new CarriageComponent[MockSignal, MOCK_CarriageUnit](physics, host)

		lazy val outboundSource = new Channel.Source[MaterialLoad, MockSignal] {
			override lazy val ref: SimRef[MockSignal] = host.self

			override def loadAcknowledged(chStart: Channel.Start[MaterialLoad, MockSignal], load: MaterialLoad)(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				log.info(s"SourceFixture: Acknowledging Load $load in channel ${chStart.channelName}")
				monitor ! Notify(s"Received Load Acknoledgement at Channel: ${chStart.channelName} with $load")
				DomainRun.same
			}
		}
		val startEndpoint = outbound.registerStart(outboundSource)

		lazy val inboundSink = new Channel.Sink[MaterialLoad, MockSignal] {
			override lazy val ref: SimRef[MockSignal] = host.self


			override def loadArrived(endpoint: Channel.End[MaterialLoad, MockSignal], load: MaterialLoad, at: Option[Int])(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				monitor ! Notify(s"Load $load arrived via channel ${endpoint.channelName}")
				ctx.signal(host.manager, LoadArrival(load, ctx.now))
				DomainRun.same
			}

			override def loadReleased(endpoint: Channel.End[MaterialLoad, MockSignal], load: MaterialLoad, at: Option[Int])(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = {
				log.debug(s"Releasing Load $load in channel ${endpoint.channelName}")
				monitor ! Notify(s"Load $load released on channel ${endpoint.channelName}")
				DomainRun.same
			}
		}
		val inboundEnd = inbound.registerEnd(inboundSink)

		lazy private val loadingProcessing: host.CTX => PartialFunction[CarriageComponent.LoadOperationOutcome, host.RUNNER] = {
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

		lazy private val unloadingProcessing: host.CTX => PartialFunction[CarriageComponent.UnloadOperationOutcome, host.RUNNER] = {
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

		lazy val INTRANSIT_LOADING: host.RUNNER = carriage.LOADING(loadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					DomainRun.same
			}
		}
		lazy val INTRANSIT_UNLOADING: host.RUNNER = carriage.UNLOADING(unloadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					DomainRun.same
			}
		}
		lazy val INTRANSIT_INDUCTING: host.RUNNER = carriage.INDUCTING(loadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					DomainRun.same
			}
		}
		lazy val INTRANSIT_DISCHARGING: host.RUNNER = inboundEnd.loadReceiver orElse carriage.DISCHARGING(unloadingProcessing) orElse {
			implicit ctx: host.CTX => {
				case signal =>
					monitor ! Notify(s"Reject Signal $signal while in Transit")
					DomainRun.same
			}
		}

		lazy val EIDLE: host.RUNNER = inboundEnd.loadReceiver orElse {
			implicit ctx: host.CTX => {
				case cmd: ELoad =>
					carriage.loadFrom(cmd.loc)
					INTRANSIT_LOADING
				case cmd: EInduct =>
					carriage.inductFrom(cmd.from, cmd.at)
					INTRANSIT_INDUCTING
				case other =>
					monitor ! Notify(s"Rejecting Command $other")
					DomainRun.same
			}
		}

		lazy val EFULL: host.RUNNER = inboundEnd.loadReceiver orElse {
			implicit ctx: host.CTX => {
				case cmd: EUnload =>
					carriage.unloadTo(cmd.loc)
					INTRANSIT_UNLOADING
				case cmd: EDischarge =>
					carriage.dischargeTo(cmd.to, cmd.at)
					INTRANSIT_DISCHARGING
				case other =>
					monitor ! Notify(s"Rejecting Command $other")
					DomainRun.same
			}
		}

		def configurer: DomainConfigure[MockSignal] = new DomainConfigure[MockSignal] {
			override def configure(config: MockSignal)(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainRun[MockSignal] = config match {
				case DoConfigure(loc, inventory) =>
					host.installManager(ctx.from)
					host.installSelf(ctx.aCtx.self)
					carriage.atLocation(loc).withInventory(inventory)
//					ctx.configureContext.signal(host.manager, CompletedConfiguration(ctx.aCtx.self))
					ctx.configureContext.reply(CompletedConfiguration(ctx.aCtx.self))
					ctx.aCtx.log.debug(s"Completed configuration and notifiying ${ctx.from}")
					EIDLE
				case other => throw new IllegalArgumentException(s"Unknown Signal; $other")
			}
		}
	}

	class CommandRelayer(inboundJobs: mutable.Map[MaterialLoad, Seq[MockSignal]], target: SimRef[MockSignal]) {
		private var _manager: SimRef[MockNotification] = _
		lazy val configurer: DomainConfigure[MockSignal] = new DomainConfigure[MockSignal] {
			override def configure(config: MockSignal)(implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal]): DomainMessageProcessor[MockSignal] = {
				config match {
					case NullConfigure =>
						_manager = ctx.from
						configurer
					case cmd: CompletedConfiguration =>
						ctx.signal(_manager, cmd)
						RUNNING
				}
			}
		}

		val RUNNING: DomainRun[MockSignal] = {
			//var busy = false
			//val loadsPending: mutable.Queue[(String, MaterialLoad)] = mutable.Queue.empty
			implicit ctx:  FullSignallingContext[MockSignal, _ <: DomainSignal] => {
				case cmd @ LoadArrival(ld, t) =>
					ctx.signal(_manager, cmd)
					inboundJobs.remove(ld).foreach(_.foreach(ctx.signal(target, _)))
					RUNNING
			}
		}
	}

}

class CarriageComponentOnChannelSpec
	extends AnyWordSpec
		with Matchers
		with AnyWordSpecLike
		with BeforeAndAfterAll
		with ClockEnabled
		with LogEnabled {
	import CarriageComponentOnChannelSpec._

	val testKit = ActorTestKit()

	override def beforeAll: Unit = {

	}

	override def afterAll: Unit = {
		testKit.shutdownTestKit()
	}

	"A Carriage" when {

		val testController = testKit.createTestProbe[ControllerMessage]
		val fixtureObserver = testKit.createTestProbe[String]

		val harnessObserver = testKit.createTestProbe[(Tick,MockNotification)]

		val shuttleHarnessProcessor = new ProcessorSink[MockNotification](harnessObserver.ref, clock)
		val shuttleHarness = testKit.spawn(shuttleHarnessProcessor.init)

		val harnessMonitor = testKit.createTestProbe[MockNotification]

		//val mockProcessorReceiver = testKit.createTestProbe[SimSignal]
		//val testActor = testKit.createTestProbe[SimSignal]

		val physics = new CarriageTravel(2, 6, 4, 8, 8)

		val chIn = new MockChannel(() => Some(10), () => Some(3), Set("c1", "c2", "c3"), 1, "inboundCh")
		val chInOps = new Channel.Ops(chIn)
		val chOut = new MockChannel(() => Some(10), () => Some(3), Set("c1", "c2", "c3"), 1, "outboundCh")
		val chOutOps = new Channel.Ops(chOut)

		val sourceFixture = new SourceFixture(chInOps)(fixtureObserver.ref, this)
		val sourceProcessor = new AgentTemplate.Wrapper("inboundSource", clock, testController.ref, fixtureConfigurer(sourceFixture)(fixtureObserver.ref))
		val sourceRef = testKit.spawn(sourceProcessor.init, "InboundSource")
		val sinkFixture = new SinkFixture(chOutOps)(fixtureObserver.ref,this)
		val sinkProcessor = new AgentTemplate.Wrapper("inboundSource", clock, testController.ref, fixtureConfigurer(sinkFixture)(fixtureObserver.ref))
		val sinkRef = testKit.spawn(sinkProcessor.init, "OutboundSink")

		val harness = new Harness(harnessMonitor.ref, physics, chInOps, chOutOps)
		val carriageProcessor = new  AgentTemplate.Wrapper[MockSignal]("underTest", clock, testController.ref, harness.configurer)
		val underTest = testKit.spawn(carriageProcessor.init, "undertest")

		val loadProbe = MaterialLoad("loadProbe")
		val loadProbe2 = MaterialLoad("loadProbe2")
		val locAt0 = OnRight(0)
		val locAt7 = OnRight(7)
		val locAt5 = OnLeft(5)
		val locAt10 = OnRight(10)


		val inboundJobs = mutable.Map.empty[MaterialLoad, Seq[MockSignal]]
		val commandRelayer = new CommandRelayer(inboundJobs, underTest)
		val commandRelayerRef = testKit.spawn(new AgentTemplate.Wrapper("commandRelayer", clock, testController.ref, commandRelayer.configurer).init, "commandRelayer")

		"A. Register Itself for configuration" should {
			clock ! RegisterMonitor(testController.ref)
			startTime()

			"A01. Send a registration message to the controller" in {
				testController.expectMessage(RegisterProcessor[MockSignal](sourceRef))
				testController.expectMessage(RegisterProcessor[MockSignal](sinkRef))
				testController.expectMessage(RegisterProcessor[MockSignal](underTest))
				testController.expectMessage(RegisterProcessor[MockSignal](commandRelayerRef))
				testController.expectMessage(RegisteredClockMonitors(1))
				testController.expectMessage(StartedOn(0L))
				testController.expectMessage(NoMoreWork(0L))
			}
			"A02 Process a Configuration Message and notify the controller when configuration is complete" in {
				enqueueConfigure(commandRelayerRef, shuttleHarness, 0L, NullConfigure)
				enqueueConfigure(underTest, commandRelayerRef, 0L, DoConfigure(locAt0.idx, Map(locAt0 -> loadProbe, locAt5 -> loadProbe2)))
				enqueueConfigure(sourceRef, sourceRef, 0L, FixtureConfigure)
				enqueueConfigure(sinkRef, sinkRef, 0L, FixtureConfigure)
				val expectedConfigurations =
					mutable.Set(RegistrationConfigurationComplete[MockSignal](sourceRef), RegistrationConfigurationComplete[MockSignal](sinkRef), RegistrationConfigurationComplete[MockSignal](underTest), RegistrationConfigurationComplete[MockSignal](commandRelayerRef))
				testController.fishForMessage(500 millis) {
					case cmd: RegistrationConfigurationComplete[MockSignal] if expectedConfigurations.contains(cmd) =>
						expectedConfigurations -= cmd
						if(expectedConfigurations isEmpty) FishingOutcome.Complete
						else FishingOutcome.Continue
					case _: NoMoreWork => FishingOutcome.ContinueAndIgnore
				}
				harnessObserver.expectMessage((0L, CompletedConfiguration(underTest)))
				testController.expectMessage(NoMoreWork(0L))
				testController.expectNoMessage
				harnessObserver.expectNoMessage
			}
			"A03 Load the tray when empty with the acquire delay and reject another command in between" in {
				val loadCommandAt0 = EInduct(chInOps.end, locAt0)
				val loadCommandToFail = EInduct(chInOps.end, locAt7)
				//val dischargeCommand = EDischarge(chOutOps.start, OnRight(10))
				inboundJobs += loadProbe -> Seq(loadCommandAt0, loadCommandToFail)
				enqueue(sourceRef, sourceRef, 1L, TestProbeMessage("First Load", loadProbe))
				val expected = 2
				var count = 0
				harnessMonitor.fishForMessage(500 millis){
					case Notify("Load MaterialLoad(loadProbe) arrived via channel inboundCh") =>
						count += 1
						if(count == expected) FishingOutcome.Complete
						else FishingOutcome.Continue
					case Notify(msg) if msg.startsWith("Reject Signal EInduct") =>
						count += 1
						if(count == expected) FishingOutcome.Complete
						else FishingOutcome.Continue
				}
				harnessMonitor.expectMessage(500 millis, Notify("Completed Loading at 22"))
				harnessMonitor.expectMessage(Notify("Load MaterialLoad(loadProbe) released on channel inboundCh"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A04 Reject a command to load again" in {
				val loadCommand = ELoad(locAt0)
				enqueue(underTest, shuttleHarness, 25L, loadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Rejecting Command ELoad(OnRight(0))"))
				harnessMonitor.expectNoMessage(500 millis)
 			}
			"A06 Reject an unload request for a full location"  in {
				val unloadCommand = EUnload(locAt5)
				enqueue(underTest, shuttleHarness, 26L, unloadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Error Loading: Target Full at 41"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A07 Discharge the tray with the original content" in {
				val unloadCommand = EDischarge(chOutOps.start, locAt5)
				enqueue(underTest, shuttleHarness, 42, unloadCommand)
				harnessMonitor.expectMessage(500 millis,Notify("Completed Unloading at 50"))
				harnessMonitor.expectNoMessage(500 millis)
			}
			"A08 Reject a command to unload again" in {
				val unloadCommand = EUnload(locAt10)
				enqueue(underTest, shuttleHarness, 65L, unloadCommand)
				harnessMonitor.expectMessage(500 millis, Notify("Rejecting Command EUnload(OnRight(10))"))
				harnessMonitor.expectNoMessage(500 millis)
			}
		}
	}
}
