/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.units.flowspec

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.saldubatech.base.Identification
import com.saldubatech.ddes.testHarness.ProcessorSink
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.test.BaseSpec.TestProbeExt
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.UnitsFixture._
import com.saldubatech.units.`abstract`.EquipmentManager
import com.saldubatech.units.carriage.Carriage
import com.saldubatech.units.lift.BidirectionalCrossSwitch
import com.saldubatech.units.shuttle.Shuttle
import com.saldubatech.units.unitsorter.{CircularPathTravel, UnitSorter, UnitSorterSignal}
import com.saldubatech.util.LogEnabled
import org.scalatest._

import scala.collection.mutable
import scala.concurrent.duration._

object VolumeGTPSpec {

	trait Job {
		val lift: Processor.Ref
		val liftCmd: BidirectionalCrossSwitch.Transfer
		val shuttle: Processor.Ref
		val shuttleCmd: Shuttle.ExternalCommand
	}
	case class InboundJob(inbound: Processor.Ref, load: MaterialLoad, sorterCmd: UnitSorter.Sort, lift: Processor.Ref, levelIdx: Int, override val liftCmd: BidirectionalCrossSwitch.Transfer, shuttle: Processor.Ref, override val shuttleCmd: Shuttle.Store) extends Job
	case class OutboundJob(outboundName: String, load: MaterialLoad, sorterCmd: UnitSorter.Sort, lift: Processor.Ref, override val liftCmd: BidirectionalCrossSwitch.Transfer, shuttle: Processor.Ref, override val shuttleCmd: Shuttle.Retrieve) extends Job

	case object ManagerConfigure extends EquipmentManager.ManagerSignal
	case class ConfigurationComplete(from: Processor.Ref) extends Identification.Impl() with EquipmentManager.Notification

	class ReactiveShuttleCommandBufferController(inboundJobs: Map[MaterialLoad, Job], outboundJobs: Map[MaterialLoad, Job], testHost: TestSuite) extends LogEnabled {
		private val obJobIt = outboundJobs.iterator
		private var _manager: Processor.Ref = _
		lazy val configurer: Processor.DomainConfigure[EquipmentManager.ManagerSignal] = new Processor.DomainConfigure[EquipmentManager.ManagerSignal] {
			override def configure(config: EquipmentManager.ManagerSignal)(implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal]): Processor.DomainMessageProcessor[EquipmentManager.ManagerSignal] = {
				config match {
					case ManagerConfigure =>
						_manager = ctx.from
						configurer
					case shuttleCfg: Shuttle.CompletedConfiguration =>
						ctx.configureContext.signal(_manager, shuttleCfg)
						ctx.configureContext.signal(_manager, ConfigurationComplete(ctx.aCtx.self))
						RUNNING
				}
			}
		}
		private def runLoad(ld: MaterialLoad)(implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal]): Boolean = {
			inboundJobs.get(ld) match {
				case None =>
					testHost.fail(s"Unexpected load $ld at lift: ${ctx.from.path.name}")
				case Some(cmd) =>
					if(cmd.shuttle == ctx.from) {
						ctx.signal(cmd.shuttle, cmd.shuttleCmd)
						true
					} else false
			}
		}

		val RUNNING: Processor.DomainRun[EquipmentManager.ManagerSignal] = {
			var busy = false
			val loadsPending = mutable.Queue.empty[MaterialLoad]
			implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal] => {
				case cmd: Shuttle.CompletedCommand =>
					if(outboundJobs.values.exists(j => j.shuttleCmd == cmd.cmd) && obJobIt.hasNext) ctx.signal(ctx.from, obJobIt.next._2.shuttleCmd)
					else if(loadsPending isEmpty) busy = false
					else busy = runLoad(loadsPending.dequeue)
					ctx.signal(_manager, cmd)
					Processor.DomainRun.same
				case Shuttle.LoadArrival(chName, ld) =>
					inboundJobs.get(ld) match {
						case None => testHost.fail(s"Unexpected load $ld at shuttle: ${ctx.from.path.name}")
						case Some(cmd) =>
							loadsPending += ld
							if(!busy) busy = runLoad(loadsPending.dequeue)
					}
					Processor.DomainRun.same
			}
		}
	}
	def reactiveShuttleController(name: String, simController: SimulationController.Ref, inboundJobs: Map[MaterialLoad, Job], outboundJobs: Map[MaterialLoad, Job], testHost: TestSuite)(implicit clock: Clock.Ref, processorCreator: Processor.ProcessorCreator)  =
		processorCreator.spawn(new Processor(name, clock, simController, new ReactiveShuttleCommandBufferController(inboundJobs, outboundJobs, testHost).configurer).init, name)

	class ReactiveLiftCommandBufferController(inboundJobs: Map[MaterialLoad, Job], outboundJobs: Map[MaterialLoad, Job], testHost: TestSuite) extends LogEnabled {
		private var _manager: Processor.Ref = null
		lazy val configurer: Processor.DomainConfigure[EquipmentManager.ManagerSignal] = new Processor.DomainConfigure[EquipmentManager.ManagerSignal] {
			override def configure(config: EquipmentManager.ManagerSignal)(implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal]): Processor.DomainMessageProcessor[EquipmentManager.ManagerSignal] = {
				config match {
					case ManagerConfigure =>
						_manager = ctx.from
						configurer
					case liftCfg: BidirectionalCrossSwitch.CompletedConfiguration =>
						ctx.configureContext.signal(_manager, liftCfg)
						ctx.configureContext.signal(_manager, ConfigurationComplete(ctx.aCtx.self))
						RUNNING
				}
			}
		}

		private def runLoad(loadsPending: mutable.Queue[(String, MaterialLoad)])(implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal]): Boolean = {
			val (chName, ld) = loadsPending.dequeue
			val jobs = if (chName.contains("aisle_sorter")) outboundJobs else inboundJobs
			jobs.get(ld) match {
				case None =>
					testHost.fail(s"Unexpected load $ld at lift: ${ctx.from.path.name}")
				case Some(cmd) =>
					if(cmd.lift == ctx.from) {
						ctx.signal(cmd.lift, cmd.liftCmd)
						true
					} else {
						testHost.fail(s"Load $ld from channel $chName received by ${ctx.from} expecting command $cmd")
						loadsPending += chName -> ld
						false
					}
			}
		}

		val RUNNING: Processor.DomainRun[EquipmentManager.ManagerSignal] = {
			var busy = false
			val loadsPending: mutable.Queue[(String, MaterialLoad)] = mutable.Queue.empty
			implicit ctx: Processor.SignallingContext[EquipmentManager.ManagerSignal] => {
				case cmd: BidirectionalCrossSwitch.CompletedCommand =>
					ctx.signal(_manager, cmd)
					if(loadsPending isEmpty) busy = false
					else busy = runLoad(loadsPending)
					Processor.DomainRun.same
				case BidirectionalCrossSwitch.LoadArrival(chName, ld) =>
					loadsPending += chName -> ld
					if(!busy) busy = runLoad(loadsPending)
					Processor.DomainRun.same
			}
		}
	}
	def reactiveLiftController(name: String, simController: SimulationController.Ref, inboundJobs: Map[MaterialLoad, Job], outboundJobs: Map[MaterialLoad, Job], testHost: TestSuite)(implicit clock: Clock.Ref, processorCreator: Processor.ProcessorCreator)  =
		processorCreator.spawn(new Processor(name, clock, simController, new ReactiveLiftCommandBufferController(inboundJobs, outboundJobs, testHost).configurer).init, name)

}

class VolumeGTPSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled {

	import VolumeGTPSpec._
	val nJobs = 10
	val nCards = 4
	val cards = (0 until nCards).map(i => s"c$i").toSet

	implicit val testKit = ActorTestKit()

	override def beforeAll: Unit = {}

	override def afterAll: Unit = testKit.shutdownTestKit()

	implicit val hostTest = this
	val testMonitorProbe = testKit.createTestProbe[String]
	implicit val testMonitor = testMonitorProbe.ref

	implicit val globalClock = testKit.spawn(Clock())
	val simControllerProbe = testKit.createTestProbe[SimulationController.ControllerMessage]
	implicit val simController = simControllerProbe.ref

	val systemManagerProbe = testKit.createTestProbe[(Clock.Tick, EquipmentManager.Notification)]
	val systemManagerProcessor = new ProcessorSink(systemManagerProbe.ref, globalClock)
	val systemManager = testKit.spawn(systemManagerProcessor.init, "systemManager")

	val sorterManagerProbe = testKit.createTestProbe[(Clock.Tick, EquipmentManager.Notification)]
	val sorterManagerProcessor = new ProcessorSink(sorterManagerProbe.ref, globalClock)
	val sorterManager = testKit.spawn(sorterManagerProcessor.init, "sorterManager")

	"A GTP BackEnd" should {
		val liftPhysics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)
		val shuttlePhysics = new Carriage.CarriageTravel(2, 6, 4, 8, 8)

		val sorterAisleA = Channel.Ops(new SorterLiftChannel(() => Some(20), cards, 1, s"sorter_aisle_A"))
		val sorterAisleB = Channel.Ops(new SorterLiftChannel(() => Some(20), cards, 1, s"sorter_aisle_B"))
		val aisleASorter = Channel.Ops(new LiftSorterChannel(() => Some(20), cards, 1, s"aisle_sorter_A"))
		val aisleBSorter = Channel.Ops(new LiftSorterChannel(() => Some(20), cards, 1, s"aisle_sorter_B"))

		val aisleA = buildAisle("AisleA", liftPhysics, shuttlePhysics, 20, 0, 0 -> sorterAisleA, 0 -> aisleASorter, Seq(2, 5))
		val aisleB = buildAisle("AisleB", liftPhysics, shuttlePhysics, 20, 0, 0 -> sorterAisleB, 0 -> aisleBSorter, Seq(2, 5))
		val aisleInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = Map(50 -> aisleASorter, 0 -> aisleBSorter)
		val aisleDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(35 -> sorterAisleA, 40 -> sorterAisleB)

		val chIb1 = new InboundInductChannel(() => Some(10L), Set("Ib1_c1"), 1, "Inbound1")
		val chIb2 = new InboundInductChannel(() => Some(10L), Set("Ib2_c1"), 1, "Inbound2")
		val inboundInducts: Map[Int, Channel.Ops[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal]] = Map(30 -> new Channel.Ops(chIb1), 45 -> new Channel.Ops(chIb2))

		val chDis1 = new OutboundDischargeChannel(() => Some(10L), Set("Ob1_c1", "Ob1_c2"), 1, "Discharge_1")
		val chDis2 = new OutboundDischargeChannel(() => Some(10L), Set("Ob2_c1", "Ob2_c2"), 1, "Discharge_2")
		val outboundDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = Map(15 -> new Channel.Ops(chDis1), 30 -> new Channel.Ops(chDis2))

		val sorterInducts: Map[Int, Channel.Ops[MaterialLoad, _, UnitSorterSignal]] = inboundInducts ++ aisleInducts
		val sorterDischarges: Map[Int, Channel.Ops[MaterialLoad, UnitSorterSignal, _]] = outboundDischarges ++ aisleDischarges

		val sorterPhysics = new CircularPathTravel(60, 25, 100)
		val sorterConfig = UnitSorter.Configuration("sorter", 200, sorterInducts, sorterDischarges, sorterPhysics)
		val sorter: Processor.Ref = UnitSorterBuilder.build(sorterConfig)

		val sources = inboundInducts.values.map(chOps => new SourceFixture(chOps)(testMonitor, this))

		val sourceProcessors = sources.zip(Seq("Inbound1", "Inbound2")).map(t => new Processor(t._2, globalClock, simController, configurer(t._1)(testMonitor)))

		val sourceRefs: Seq[Processor.Ref] = sourceProcessors.map(t => testKit.spawn(t.init, t.processorName)).toList

		val destinations = outboundDischarges.values.toSeq.map {
			case chOps: Channel.Ops[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType] => new SinkFixture(chOps)(testMonitor, this)
		}
		val destinationProcessors = destinations.zipWithIndex.map { case (dstSink, idx) => new Processor(s"discharge_$idx", globalClock, simController, configurer(dstSink)(testMonitor)) }
		val destinationRefs: Seq[Processor.Ref] = destinationProcessors.map(proc => testKit.spawn(proc.init, proc.processorName))

		val slotDimension = (0 until 20)
		val sideDimension: Seq[Int => Carriage.SlotLocator] = Seq(Carriage.OnLeft, Carriage.OnRight)
		val inboundLevelDimension =
			for {
				((lift, shuttles), inboundChannel) <- Seq(aisleA -> sorterAisleA, aisleB -> sorterAisleB)
				(levelIdx, shuttle) <- shuttles
			} yield (lift, levelIdx, shuttle, inboundChannel)

		var i = -1
		val candidateInboundJobs =
			for {
				slotIdx <- slotDimension
				side <- sideDimension
				(lift, levelIdx, shuttle, inboundChannel) <- inboundLevelDimension
			} yield {
				i += 1
				val aisle = if ((i % 4)/2 == 0) "A" else "B"
				val slot = side(slotIdx)
				val load = MaterialLoad(s"${aisle}_${levelIdx}_${slot}::$i")
				val shuttleChannelName = s"shuttle_Aisle${aisle}_${levelIdx}_in"
				InboundJob(sourceRefs(i % 2), load, UnitSorter.Sort(load, inboundChannel.ch.name), lift, levelIdx, BidirectionalCrossSwitch.Transfer(inboundChannel.ch.name, shuttleChannelName), shuttle, Shuttle.Store(shuttleChannelName, slot))
			}

		val inboundJobs = candidateInboundJobs.take(nJobs)
		//println(s"### InboundJobs:\n\t###${inboundJobs.mkString("\n\t###")}")
		var j = -1
		val outboundJobs = inboundJobs.map {
			ij => {
				j += 1
				val expectedDischarge = s"Discharge_${j % 2 + 1}"
				val aisle = if ((j % 4)/2 == 0) "A" else "B"
				val shuttleChannelName = s"shuttle_Aisle${aisle}_${ij.levelIdx}_out"
				val outboundChannel = if (j % 2 == 0) outboundDischarges.values.head.ch.name else outboundDischarges.values.tail.head.ch.name
				val liftChannelName = s"aisle_sorter_$aisle"
				OutboundJob(expectedDischarge, ij.load, UnitSorter.Sort(ij.load, outboundChannel), ij.lift, BidirectionalCrossSwitch.Transfer(shuttleChannelName, liftChannelName), ij.shuttle, Shuttle.Retrieve(ij.shuttleCmd.to, shuttleChannelName))
			}
		}

		val inboundJobsMap = inboundJobs.map(j => j.load -> j).toMap
		val outboundJobsMap = outboundJobs.map(j => j.load -> j).toMap
		val shuttleManager = (name: String) => reactiveShuttleController(name+"_controller", simController, inboundJobsMap, outboundJobsMap, this)
		val liftManager = (name: String) => reactiveLiftController(name+"_controller", simController, inboundJobsMap, outboundJobsMap, this)
		val allShuttles =	aisleA._2.map(_._2) ++ aisleB._2.map(_._2)
		val shuttleManagers = allShuttles.map(sh => sh -> shuttleManager(sh.path.name)).toMap
		val liftManagers = Map(aisleA._1 -> liftManager(aisleA._1.path.name), aisleB._1 -> liftManager(aisleB._1.path.name))

		"A. Configure itself" when {
			"A01. Time is started they register for Configuration" in {
				val actors = sourceRefs ++ shuttleManagers.values ++ liftManagers.values ++ destinationRefs ++ Seq(sorter, aisleA._1, aisleB._1) ++ aisleA._2.map(_._2) ++ aisleB._2.map(_._2)
				val actorsToRegister: mutable.Set[Processor.Ref] = mutable.Set(actors: _*)
				globalClock ! Clock.StartTime()
				simControllerProbe.fishForMessage(3 second) {
					case Processor.RegisterProcessor(pr) =>
						if (actorsToRegister.contains(pr)) {
							actorsToRegister -= pr
							if (actorsToRegister isEmpty) FishingOutcome.Complete
							else FishingOutcome.Continue
						} else {
							FishingOutcome.Fail(s"Unexpected processor registration received $pr")
						}
				}
				actorsToRegister.isEmpty should be(true)
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A02. Process the configuration of its elements" in {
				shuttleManagers.values.foreach(_ ! Processor.ConfigurationCommand(systemManager, 0L, ManagerConfigure))
				liftManagers.values.foreach(_ ! Processor.ConfigurationCommand(systemManager, 0L, ManagerConfigure))
				sorter ! Processor.ConfigurationCommand(sorterManager, 0L, UnitSorter.NoConfigure)
				sorterManagerProbe.expectMessage(0L -> UnitSorter.CompletedConfiguration(sorter))
				val systemManagerProbeExt = new TestProbeExt(systemManagerProbe)
				aisleA._1 ! Processor.ConfigurationCommand(liftManagers(aisleA._1), 6L, BidirectionalCrossSwitch.NoConfigure)
				aisleB._1 ! Processor.ConfigurationCommand(liftManagers(aisleB._1), 6L, BidirectionalCrossSwitch.NoConfigure)
				allShuttles.foreach(sh => sh ! Processor.ConfigurationCommand(shuttleManagers(sh), 10L, Shuttle.NoConfigure))

				val shuttleCompletes = allShuttles.map(sh => (10L -> Shuttle.CompletedConfiguration(sh))).toList
				val shuttleManagerCompletes = shuttleManagers.values.map(shm => (10L -> ConfigurationComplete(shm)))
				val liftManagerCompletes = liftManagers.values.map(lm => (6L -> ConfigurationComplete(lm)))
				systemManagerProbeExt.expectMessages(
					(6L -> BidirectionalCrossSwitch.CompletedConfiguration(aisleA._1)) :: (6L -> BidirectionalCrossSwitch.CompletedConfiguration(aisleB._1))
						:: shuttleCompletes ++ shuttleManagerCompletes ++ liftManagerCompletes : _*
				)
				val actorsToConfigure = mutable.Set((Seq(sorter, aisleA._1, aisleB._1) ++ allShuttles ++ liftManagers.values ++ shuttleManagers.values): _*)
				var nShuttles = 6
				var nShuttlesToRegister = 6
				simControllerProbe.fishForMessage(1000 millis) {
					case Processor.CompleteConfiguration(pr) if actorsToConfigure.contains(pr) =>
						actorsToConfigure -= pr
						if (actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.CompleteConfiguration if nShuttles > 0 =>
						nShuttles -= 1
						if (actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case msg: Processor.RegisterProcessor if nShuttlesToRegister > 0 =>
						nShuttlesToRegister -= 1
						if (actorsToConfigure.nonEmpty || nShuttles > 0 || nShuttlesToRegister > 0) FishingOutcome.Continue
						else FishingOutcome.Complete
					case other => FishingOutcome.Fail(s"Unexpected message: $other with remaining shuttles to register $nShuttlesToRegister and toConfigure $nShuttles")
				}
				simControllerProbe.expectNoMessage(500 millis)
			}
			"A03. Sinks and Sources accept Configuration" in {
				sourceRefs.foreach(act => act ! Processor.ConfigurationCommand(sorterManager, 0L, UpstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $UpstreamConfigure")
				destinationRefs.foreach(ref => ref ! Processor.ConfigurationCommand(sorterManager, 0L, DownstreamConfigure))
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				testMonitorProbe.expectMessage(s"Received Configuration: $DownstreamConfigure")
				val actorsToConfigure: mutable.Set[Processor.Ref] = mutable.Set(sourceRefs ++ destinationRefs: _*)
				simControllerProbe.fishForMessage(500 millis) {
					case Processor.CompleteConfiguration(pr) =>
						if (actorsToConfigure.contains(pr)) {
							actorsToConfigure -= pr
							if (actorsToConfigure isEmpty) FishingOutcome.Complete
							else FishingOutcome.Continue
						} else {
							FishingOutcome.Fail(s"Unexpected processor registration received $pr")
						}
				}
				actorsToConfigure.isEmpty should be(true)
			}
		}
/*		"B. Filling the storage" when {
			"B01. Executing the commands" in {
				var count = 0
				inboundJobs.foreach {job =>
					globalClock ! Clock.Enqueue(sorter, Processor.ProcessCommand(sorterManager, 20, job.sorterCmd))
					val source = sourceRefs(count%2)
					count += 1
					globalClock ! Clock.Enqueue(source, Processor.ProcessCommand(source, 70L, TestProbeMessage(s"InboundLoad#$count", job.load)))
				}
				var completedCommands = 0
				systemManagerProbe.fishForMessage(3 seconds) {
					case (tick, Shuttle.CompletedCommand(cmd)) =>
						completedCommands += 1
						if(!inboundJobs.exists(_.shuttleCmd == cmd)) FishingOutcome.Fail(s"Unknown Shuttle Command: $cmd at $tick")
						else if(completedCommands == 2*inboundJobs.size) FishingOutcome.Complete
						else FishingOutcome.Continue
					case (tick, BidirectionalCrossSwitch.CompletedCommand(cmd)) =>
						completedCommands += 1
						if(!inboundJobs.exists(_.liftCmd == cmd)) FishingOutcome.Fail(s"Unknown Lift Command: $cmd at $tick")
						else if(completedCommands == 2*inboundJobs.size) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Unexpected signal $other")
				}
				var sorterLoadsReceived = 0
				var sorterCompletedCommands = 0
				def isSorterDone = sorterCompletedCommands == inboundJobs.size && sorterLoadsReceived == inboundJobs.size
				sorterManagerProbe.fishForMessage(3 seconds){
					case (tick, UnitSorter.LoadArrival(load, channel)) =>
						sorterLoadsReceived += 1
						if(isSorterDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case (tick, UnitSorter.CompletedCommand(cmd)) =>
						sorterCompletedCommands += 1
						if(isSorterDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Unexpected Received $other")
				}
				var testMessages = 0
				def areTestMessagesDone = testMessages == nJobs*2
				testMonitorProbe.fishForMessage(500 millis){
					case str: String if str.startsWith("FromSender: InboundLoad#") =>
						testMessages += 1
						if(areTestMessagesDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case str: String if str.startsWith("Received Load Acknowledgement through Channel: Inbound1") =>
						testMessages += 1
						if(areTestMessagesDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case str: String if str.startsWith("Received Load Acknowledgement through Channel: Inbound2") =>
						testMessages += 1
						if(areTestMessagesDone) FishingOutcome.Complete
						else FishingOutcome.Continue
					case other => FishingOutcome.Fail(s"Unexpected testMonitorMessage $other")
				}
				sorterManagerProbe.expectNoMessage(500 millis)
				systemManagerProbe.expectNoMessage(500 millis)
				simControllerProbe.expectNoMessage(500 millis)
				testMonitorProbe.expectNoMessage(500 millis)
			}
		}*/
	}
		/*
		"B. Transfer a load from one of its inbound sources" when {
			val probeLoad = MaterialLoad("FirstLoad")
			val sorterCommand = UnitSorter.Sort(probeLoad, sorterAisleA.ch.name)
			val liftCommand = BidirectionalCrossSwitch.Transfer(sorterAisleA.ch.name, "shuttle_AisleA_2_in")
			val shuttleCommand = Shuttle.Store("shuttle_AisleA_2_in", Carriage.OnLeft(7))
			"B01. Receives commands in advance of the load" in {
				globalClock ! Clock.Enqueue(sorter, Processor.ProcessCommand(systemManager, 64L, sorterCommand))
				globalClock ! Clock.Enqueue(aisleA._1, Processor.ProcessCommand(systemManager, 64L, liftCommand))
				globalClock ! Clock.Enqueue(aisleA._2.head._2, Processor.ProcessCommand(systemManager, 64L, shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
			"B02. The Load is sent to the sorter" in  {
				val probeLoadMessage = TestProbeMessage("FirstLoad", probeLoad)
				sourceRefs.head ! Processor.ProcessCommand(sourceRefs.head, 70L, probeLoadMessage)
				testMonitorProbe.expectMessage("FromSender: FirstLoad")
				systemManagerProbe.expectMessage(80L -> UnitSorter.LoadArrival(probeLoad, chIb1.name))
				systemManagerProbe.expectMessage(100L -> UnitSorter.CompletedCommand(sorterCommand))
				systemManagerProbe.expectMessage(140L -> BidirectionalCrossSwitch.CompletedCommand(liftCommand))
				systemManagerProbe.expectMessage(170L -> Shuttle.CompletedCommand(shuttleCommand))
				systemManagerProbe.expectNoMessage(500 millis)
			}
		}
		*/
}
