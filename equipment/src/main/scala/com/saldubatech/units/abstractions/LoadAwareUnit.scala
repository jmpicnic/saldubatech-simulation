package com.saldubatech.units.abstractions

import com.saldubatech.base.Identification
import com.saldubatech.ddes.Clock.Tick
import com.saldubatech.ddes.Processor
import com.saldubatech.transport.{ChannelConnections, MaterialLoad}

import scala.collection.mutable
object LoadAwareUnit {
	private def orderer[T] = new Ordering[(Tick, T)]{
		override def compare(x: (Tick, T), y: (Tick, T)) = x._1.compare(y._1)
	}
}

trait LoadAwareUnit[HOST_SIGNAL >: ChannelConnections.ChannelSourceSink <: Identification]  extends CarriageUnit[HOST_SIGNAL] {

	import LoadAwareUnit._

	val maxPendingCommands: Int
	protected val IDLE_BEHAVIOR: RUNNER

	type LOAD_BASED = {val load: MaterialLoad}
	type COMMAND_FOR_LOAD <: EXTERNAL_COMMAND with LOAD_BASED

	type PRIORITY_COMMAND <: EXTERNAL_COMMAND

	protected def matchLoad(ld1: MaterialLoad, ld2: MaterialLoad) = ld1 == ld2

	private implicit val fifoOrderingExtCmd = orderer[EXTERNAL_COMMAND]
	private implicit val fifoOrderingLoadCmd = orderer[COMMAND_FOR_LOAD]
	private implicit val fifoOrderingPrioCmd = orderer[PRIORITY_COMMAND]
	private implicit val fifoOrderingLoad = orderer[MaterialLoad]
	private val priority = mutable.SortedSet.empty[(Tick, PRIORITY_COMMAND)]
	private val ready = mutable.SortedSet.empty[(Tick, EXTERNAL_COMMAND)]
	private val unmatchedCommands = mutable.SortedSet.empty[(Tick, COMMAND_FOR_LOAD)]
	private val unmatchedLoads = mutable.SortedSet.empty[(Tick, MaterialLoad)]

	def +=(entry: (Tick, EXTERNAL_COMMAND))(implicit ctx: CTX): Unit = {
		if (ready.size + unmatchedCommands.size == maxPendingCommands) ctx.signal(manager, maxCommandsReached(entry._2))
		else if(ready.exists(_._2.uid == entry._2.uid) || priority.exists(_._2.uid == entry._2.uid)) {}
		else entry match {
			case (tick, lcmd: COMMAND_FOR_LOAD) if unmatchedLoads.exists(e => matchLoad(e._2, lcmd.load)) =>
				lcmd match {
					case pCmd: PRIORITY_COMMAND => priority += tick -> pCmd
					case _ => ready += entry
				}
				unmatchedCommands -= tick -> lcmd
			case (tick, pCmd: PRIORITY_COMMAND) =>
				priority += tick -> pCmd
			case (tick, cmd) => ready += entry
		}
	}

	def +=(entry: (Tick, MaterialLoad)): Unit = entry match {
		case (tick, ld) =>
			unmatchedCommands.find(e => matchLoad(e._2.load, ld)).foreach { e =>
				e._2 match {
					case pCmd: PRIORITY_COMMAND => priority += tick -> pCmd
					case _ => ready += e
				}
				unmatchedCommands -= e
			}
	}

	private var working: Option[EXTERNAL_COMMAND] = None

	override protected def executeCommand(cmd: EXTERNAL_COMMAND)(body: => RUNNER)(implicit ctx: CTX): RUNNER = {
		this += ctx.now -> cmd
		triggerCommand(body)
	}

	private def triggerCommand(body: => RUNNER)(implicit ctx: CTX): RUNNER = {
		if (working isEmpty) {
			val reifiedBody = body(ctx) // Needed to avoid executing any side effects more than once
			(priority.find(e => reifiedBody.isDefinedAt(e._2)) orElse ready.find(e => reifiedBody.isDefinedAt(e._2)))
				.map { e =>
					working = Some(e._2)
					reifiedBody(e._2)
				}.getOrElse(wrapBehavior(IDLE_BEHAVIOR))
		} else Processor.DomainRun.same
	}

	override protected def completeCommand(next: => RUNNER, notifier: EXTERNAL_COMMAND => NOTIFICATION)(implicit ctx: CTX): RUNNER =
		if (working nonEmpty) {
			val n = next // To avoid evaluation more than once
			ctx.signal(manager, working.head)
			working match {
				case Some(pCmd: PRIORITY_COMMAND) => priority.find(_._2 == pCmd).map(priority -= _)
				case Some(cmd) => ready.find(_._2 == cmd).map(ready -= _)
			}
			working = None
			triggerCommand(n)
			n
		} else Processor.DomainRun.same

	protected def wrapBehavior(behavior: => RUNNER)(implicit ctx: CTX): RUNNER = Processor.DomainRun[HOST_SIGNAL]{
		case cmd: EXTERNAL_COMMAND => executeCommand(cmd)(behavior)
	} orElse behavior

	protected def maxCommandsReached(cmd: EXTERNAL_COMMAND): NOTIFICATION
}