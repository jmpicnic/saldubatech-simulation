/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.util.Timeout
import com.saldubatech.events.{EventCollector, EventSpooler}
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

object Gateway {
  def apply(system: ActorSystem, eventSpooler: Option[EventSpooler]  = None): Gateway =
    new Gateway(system, eventSpooler)

	final case class RegisterSim(aRef: ActorRef)
	final case class CompleteConfiguration(aRef: ActorRef)
	final case class Configure(config: Any*)
	final case class Shutdown()
	final case class IsShutdownComplete()
	final case class CheckState()
	final case class KILL()

	object SimulationState extends Enumeration {
		val NEW, CONFIGURING, PENDING, READY, RUNNING, SHUTTINGDOWN, STOPPED = Value
	}

	var gracefulShutdown: Option[() => Unit] = None

}

class Gateway(_system: ActorSystem, eventSpooler: Option[EventSpooler] = None,
              externalClock: Option[ActorRef] = None) {
  import Epoch.{Action, ActionRequest}
  import Gateway._
  import GlobalClock._//{CompleteAction, RegisterTimeMonitor, Registered}

	val log = Logger(Gateway.getClass.toString)
	private val system = _system

	val eventCollector: Option[ActorRef] =
		if(eventSpooler isDefined)
			Some(system.actorOf(EventCollector.props(eventSpooler get), "globalEventCollector"))
		else None
  //  private val clock : ActorRef = system.actorOf(Props(classOf[GlobalClock], epoch), "GlobalClock")
  protected val clock : ActorRef =
	  if(externalClock isDefined) externalClock get
	  else system.actorOf(GlobalClock.props(eventCollector), name = "GlobalClock")



	protected val watcher: ActorRef = system.actorOf(Props(new Actor with ActorLogging{
		val watchees: mutable.Set[ActorRef] = mutable.Set.empty
		private val uninitialized: mutable.Set[ActorRef] = mutable.Set.empty
		private val initialized: mutable.Set[ActorRef] = mutable.Set.empty
		private var completedConfiguration = false
		private var startingAt: Option[Long] = None
		private var state = SimulationState.NEW

		clock ! RegisterTimeMonitor(self)

		override def receive: Receive = control orElse operation



		def operation: Receive = {
				case a: Any =>
					state match {
						case SimulationState.NEW => brandNew(a)
						case SimulationState.CONFIGURING => configuring(a)
						case SimulationState.PENDING => pending(a)
						case SimulationState.READY => ready(a)
						case SimulationState.RUNNING =>	running(a)
						case SimulationState.SHUTTINGDOWN =>	shuttingdown(a)
						case SimulationState.STOPPED => stopped(a)
					}
		}

		def control: Receive = {
			case CheckState() => sender ! state
			case Registered(r, n) =>
			// do nothing for now. This is just the response from GlobalClock
				log.debug(s"Registered ${r.path.name}, total $n")
			case Shutdown() =>
				state = SimulationState.SHUTTINGDOWN
		}

		private def brandNew: Receive = {
			case RegisterSim(aRef) =>
				state = SimulationState.CONFIGURING
				self ! RegisterSim(aRef)
		}

		private def running: Receive = {
			case NotifyAdvance(from, to) =>
				log.debug(s"Advancing Clock Notification from $from to $to")
			case NoMoreWork(at) =>
				state = SimulationState.SHUTTINGDOWN
				clock ! StopTime()
		}

		private def shuttingdown: Receive = {
			case NotifyAdvance(from, to) =>
				log.debug(s"Advancing Clock Notification from $from to $to")
			case NoMoreWork(at) =>
				clock ! StopTime()
			case ClockShuttingDown(at) =>
				if (gracefulShutdown isDefined) gracefulShutdown
				gracefulShutdown.foreach(_ ())
				state = SimulationState.STOPPED
				self ! KILL()
			case _ =>  // Ignore all other messages
		}

		private def stopped: Receive = {
			case KILL() =>
				eventCollector.foreach(_ ! Shutdown())
				self ! PoisonPill
				//context.system.terminate() not here. termination done in collector after it completes its job.
			case a: Any => //assert(false, s"Simulation Stopped, should not have received $a")
		}

		private def ready: Receive = {
			case StartTime(at) =>
				log.debug("Processing Start while in READY")
				startingAt = at
				clock ! StartTime(at)
				log.debug("Transition from READY to RUNNING")
				state = SimulationState.RUNNING
			case act: ActionRequest =>
				clock ! Enqueue(act)
				log.debug(s"S$state -- ending Enqueue request to clock: $act")
		}

		private def pending: Receive = {
			case act: ActionRequest =>
				clock ! Enqueue(act)
				log.debug(s"$state -- Sending Enqueue request to clock: $act")
			case CompleteConfiguration(aRef) =>
				if(uninitialized contains aRef) uninitialized -= aRef
				else if (initialized contains aRef) initialized -= aRef
				else initialized += aRef
				log.debug(s"Received configuration confirmation from ${aRef.path.name}, " +
					s"pending: ${uninitialized.size}, advised: ${initialized.size}")
				if(uninitialized.isEmpty && initialized.isEmpty) {
					log.debug("Configuration is complete: Transition from PENDING to RUNNING")
					clock ! StartTime(startingAt)
					state = SimulationState.RUNNING
				}
		}

		private def configuring: Receive = {
			case RegisterSim(aRef) =>
				watchees += aRef
				uninitialized += aRef
				log.debug(s"Received new Actor: ${aRef.path.name}, " +
					s"pending: ${uninitialized.size}, advised: ${initialized.size}")
			case CompleteConfiguration(aRef) =>
				if(uninitialized contains aRef) uninitialized -= aRef
				else if (initialized contains aRef) initialized -= aRef
				else initialized += aRef
				log.debug(s"Received configuration confirmation from ${aRef.path.name}, " +
					s"pending: ${uninitialized.size}, advised: ${initialized.size}")
				if(uninitialized.isEmpty && initialized.isEmpty) {
					log.debug("Configuration is complete: Transition CONFIGURING to READY")
					completedConfiguration = true
					state = SimulationState.READY
				}
			case StartTime(at) =>
				log.debug("Processing Start: Transition CONFIGURING to PENDING")
				startingAt = at
				state = SimulationState.PENDING
			case act: ActionRequest =>
				clock ! Enqueue(act)
				log.debug(s"Sending Enqueue request to clock: $act")
		}

		override def postStop(): Unit = {
			//system.terminate() Do not kill it here because it kills the spooling to the DB.
		}
	}), "SimulationWatcher")

	def isConfigurationComplete: Future[SimulationState.Value] = {
		implicit val timeout: Timeout = Timeout(5 seconds)
		(watcher ? CheckState()).mapTo[SimulationState.Value]
	}

	def injectInitialAction(to: ActorRef, msg: Any, at: Long = 0L): Unit = {
		watcher ! ActionRequest(watcher, to, msg, at)
	}

	def simActorOf(actorProps: Props, name: String): ActorRef = {
		val actorRef = system.actorOf(actorProps, name)
		watcher ! RegisterSim(actorRef)
		actorRef
	}

	def configure(act: ActorRef, configureMsg: Any*): Unit = {
		act ! Configure(configureMsg: _*)
	}

	def completedConfiguration(actor: ActorRef): Unit = watcher ! CompleteConfiguration(actor)
  /*def tellTo(from: ActorRef, to: ActorRef, msg: Any, now: Long, delay: Long = 0): Envelope = {
	  if (delay > 0) Enqueue(ActionRequest(from, to, msg, delay)).act(clock, from, to)
	  else ActNow(Action(from, to, msg, now)).act(clock, from, to)
  }*/

	def observeTime(obs: ActorRef): Unit = {
		clock ! RegisterTimeMonitor(obs)
	}

	def activate(): Unit = {
		watcher ! StartTime(Some(0L))
	}
	def shutdown(): Unit = {
		//implicit val timeout = Timeout(10 seconds)
		//val futureWatch = watcher ? IsShutdownComplete()
		watcher ! Shutdown()//clock ! StopTime()
		//Await.result[Any](futureWatch, 10 seconds)
		//watcher ! KILL()
	}

	def receiveAction(action: Action): Unit = clock ! StartActionOnReceive(action)
	def completeAction(action: Action): Unit = {
		log.debug(s"Notifying Complete Action: $action")
		clock ! CompleteAction(action)
	}

	def actNow(action: Action): Unit = clock ! ActNow(action)
	def enqueue(action: ActionRequest): Unit = clock ! Enqueue(action)

	def installShutdown(shutdown: () => Unit): Unit = {
		assert(gracefulShutdown isEmpty, "Attempted to double install shutdown")
		gracefulShutdown = Some(shutdown)
	}
}
