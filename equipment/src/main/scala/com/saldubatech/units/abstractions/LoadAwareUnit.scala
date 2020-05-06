package com.saldubatech.units.abstractions

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor
import com.saldubatech.protocols.Equipment
import com.saldubatech.transport.MaterialLoad
import com.saldubatech.util.LogEnabled

import scala.collection.mutable
import scala.reflect.ClassTag
object LoadAwareUnit {
	private def orderer[T] = new Ordering[(Tick, T)]{
		override def compare(x: (Tick, T), y: (Tick, T)) = x._1.compare(y._1)
	}
}

trait LoadAwareUnit[HOST_SIGNAL >: Equipment.ChannelSignal <: Identification]
	extends EquipmentUnit[HOST_SIGNAL] with InductDischargeUnit[HOST_SIGNAL] with LogEnabled {

	import LoadAwareUnit._

	protected val maxPendingCommands: Int

	type LOAD_BASED = {val load: MaterialLoad}
	type INBOUND_LOAD_COMMAND <: EXTERNAL_COMMAND with LOAD_BASED

	type PRIORITY_COMMAND <: EXTERNAL_COMMAND

	protected def matchLoad(ld1: MaterialLoad, ld2: MaterialLoad) = ld1 == ld2

	private implicit val fifoOrderingExtCmd = orderer[EXTERNAL_COMMAND]
	private implicit val fifoOrderingLoadCmd = orderer[INBOUND_LOAD_COMMAND]
	private implicit val fifoOrderingPrioCmd = orderer[PRIORITY_COMMAND]
	private implicit val fifoOrderingLoad = orderer[MaterialLoad]
	private val priority: mutable.SortedSet[(Tick, PRIORITY_COMMAND)] = mutable.SortedSet.empty[(Tick, PRIORITY_COMMAND)]
	private val ready: mutable.SortedSet[(Tick, EXTERNAL_COMMAND)] = mutable.SortedSet.empty[(Tick, EXTERNAL_COMMAND)]
	private val unmatchedCommands = mutable.SortedSet.empty[(Tick, INBOUND_LOAD_COMMAND)]
	private val unmatchedLoads = mutable.SortedSet.empty[(Tick, MaterialLoad)]

	protected def +=(entry: (Tick, EXTERNAL_COMMAND))(implicit ctx: CTX, prioCT: ClassTag[PRIORITY_COMMAND], ldCmd: ClassTag[INBOUND_LOAD_COMMAND]): Unit = {
		if (ready.size + unmatchedCommands.size == maxPendingCommands) ctx.signal(manager, maxCommandsReached(entry._2))
		else if(ready.exists(_._2.uid == entry._2.uid) || priority.exists(_._2.uid == entry._2.uid)) {}
		else entry match {
			case (tick, lcmd: INBOUND_LOAD_COMMAND) if unmatchedLoads.exists(e => matchLoad(e._2, lcmd.load)) =>
				unmatchedLoads.find(le => matchLoad(le._2, lcmd.load)).foreach {
					loadEntry =>
						lcmd match {
							case pCmd: PRIORITY_COMMAND => priority += tick -> pCmd
							case _ => ready += entry
						}
						unmatchedLoads -= loadEntry
				}
			case (tick, cmd: INBOUND_LOAD_COMMAND) => unmatchedCommands += tick -> cmd
			case (tick, pCmd: PRIORITY_COMMAND) => priority += tick -> pCmd
			case e => ready += e
		}
	}

	protected def gotLoad(entry: (Tick, MaterialLoad), onChannel: String)(implicit ctx: CTX, prioCT: ClassTag[PRIORITY_COMMAND]): Unit = entry match {
		case (tick, ld) =>
			if(unmatchedCommands.find(e => matchLoad(e._2.load, ld)).map { case (cmdTick, cmd) =>
				cmd match {
					case pCmd: PRIORITY_COMMAND => priority += tick -> pCmd
					case _ => ready += tick -> cmd
				}
				unmatchedCommands -= cmdTick -> cmd
			} isEmpty) {
				ctx.signal(manager, loadArrival(onChannel, entry._2))
				unmatchedLoads += entry
		}
	}

	private var working: Option[(Tick, EXTERNAL_COMMAND)] = None

	protected def continueCommand(runner: RUNNER, isApplicable: EXTERNAL_COMMAND => Boolean = _ => true)
	                             (implicit extTag: ClassTag[EXTERNAL_COMMAND], prioTag: ClassTag[PRIORITY_COMMAND], ldCmd: ClassTag[INBOUND_LOAD_COMMAND]): RUNNER = {
		val cmdHandler: RUNNER = {
			implicit ctx: CTX => {
				case cmd: EXTERNAL_COMMAND =>
					this += ctx.now -> cmd
					triggerNext(Processor.DomainRun.same, isApplicable) //orElse commandContinue(runner, isApplicable)
			}
		}
		cmdHandler orElse runner
	}

	import scala.reflect.ClassTag
	protected def triggerNext(body: => RUNNER, isApplicable: EXTERNAL_COMMAND => Boolean = _ => true)(implicit ctx: CTX, prioCT: ClassTag[PRIORITY_COMMAND]): RUNNER = {
		if (working isEmpty) {
			(priority.find(e => isApplicable(e._2)) orElse ready.find(e => isApplicable(e._2))) foreach { e: (Tick, EXTERNAL_COMMAND) =>
				working = Some(e)
				e match {
					case (tick, pCmd: PRIORITY_COMMAND) => priority -= tick -> pCmd //if prioCT.getClass.isAssignableFrom(pCmd.getClass) =>
					case (tick, cmd) => ready -= e
				}
				ctx.signalSelf(execSignal(e._2))
			}
		}
		body
	}

	protected def kickBack(implicit ctx: CTX, extTag: ClassTag[EXTERNAL_COMMAND], prioTag: ClassTag[PRIORITY_COMMAND], ldCmd: ClassTag[INBOUND_LOAD_COMMAND]): Unit = {
		if(working nonEmpty) {
			working.foreach(this += _)
			working = None
		}
	}

	protected def completeCommand(next: => RUNNER, notifier: EXTERNAL_COMMAND => NOTIFICATION)(implicit ctx: CTX, extTag: ClassTag[EXTERNAL_COMMAND], prioCT: ClassTag[PRIORITY_COMMAND], ldCmd: ClassTag[INBOUND_LOAD_COMMAND]): RUNNER = {
		if (working nonEmpty) {
			ctx.signal(manager, notifier(working.head._2))
			working = None
			triggerNext(continueCommand(next))
		} else Processor.DomainRun.same
	}
	protected def maxCommandsReached(cmd: EXTERNAL_COMMAND): NOTIFICATION
	protected def execSignal(cmd: EXTERNAL_COMMAND): HOST_SIGNAL
	protected def loadArrival(chName: String, ld: MaterialLoad): NOTIFICATION
}