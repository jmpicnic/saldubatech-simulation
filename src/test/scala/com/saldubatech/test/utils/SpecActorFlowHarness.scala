/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */


package com.saldubatech.test.utils

import akka.actor.{ActorRef, Props}
import com.saldubatech.ddes.SimActorImpl.Configuring
import com.saldubatech.ddes.SimActor.nullProcessing
import com.saldubatech.ddes.SimActor.Processing
import com.saldubatech.ddes.{Gateway, SimActorImpl, SimActor}
import com.saldubatech.utils.Boxer._

import scala.collection.{AbstractIterable, AbstractIterator, mutable}

object SpecActorFlowHarness {
	def simActor(trigger: HarnessTrigger,
	             actions: Flow,
	             name: String,
	             gw: Gateway,
	             testProbe: Option[ActorRef] = None,
	             configurer: HarnessConfigurer = h => {
		             case _ =>
	             }): ActorRef =
		gw.simActorOf(Props(new SpecActorFlowHarness(trigger, actions, name, gw, testProbe, configurer)), name)

	case class KickOff()

	type HarnessTrigger = (SimActor, ActorRef, Long) => Unit
	type HarnessStep = (SimActor, ActorRef, Long) => Processing
	type HarnessConfigurer = SpecActorFlowHarness => Configuring


	abstract class Flow(val name: String) {
		def isDefinedAt(host: SimActor, from: ActorRef, at: Long, msg: Any): Boolean = {
			val candidate = action(host, from, at)
			candidate.isDefined && candidate.!.isDefinedAt(msg)
		}

		def action(host: SimActor, from: ActorRef, at: Long): Option[Processing]
	}

	class SimpleFlow(name: String, body: HarnessStep) extends Flow(name) {
		private var done = false

		def action(host: SimActor, from: ActorRef, at: Long): Option[Processing] =
			if (done) None else {Some[Processing]({
				case a: Any if body(host, from, at).isDefinedAt(a) =>
					done = true
					Some(body(host, from, at))
			})
			}
	}

	class SeqFlow(_name: String, steps: Flow*) extends Flow(_name) {
		var idx: Int = 0

		override def action(host: SimActor, from: ActorRef, at: Long): Option[Processing] = {
			if (idx < steps.length) {
				val candidate = steps(idx).action(host, from, at)
				if (candidate isDefined) candidate else {
					idx += 1
					action(host, from, at)
				}
			} else None
		}

		class NFlows(_name: String, expected: Int, alternatives: Flow*)(implicit next: Option[Flow]) extends Flow(_name) {
			assert(expected <= alternatives.length,
				s"Cannot expect more matched ($expected) than the number of alternatives supported (${alternatives.length}")
			val remaining: mutable.ArrayBuffer[Flow] = mutable.ArrayBuffer(alternatives: _*)

			override def action(host: SimActor, from: ActorRef, at: Long): Option[Processing] =
				if (remaining.length > alternatives.length - expected)
					Some({
						case msg: Any if remaining.exists(f => f.isDefinedAt(host, from, at, msg)) =>
							val candidate = remaining.find(f => f.action(host, from, at).isDefined)
							if (candidate isDefined) {
								remaining -= candidate.!
								candidate.!.action(host, from, at).!.apply(msg)
							} else nullProcessing
					})
				else None
		}

		class AllFlows(_name: String, alternatives: Flow*)(implicit next: Option[Flow])
			extends NFlows(_name, alternatives.length, alternatives: _*)

		class AnyFlow(_name: String, alternatives: Flow*)(implicit next: Option[Flow])
			extends NFlows(_name, 1, alternatives: _*)


		def nopStep(msg: String = "Step: "): HarnessStep = (host, _, at) => {
			case a: Any =>
				host.log.info(s"###### $msg $a at $at")
		}

		val nopTrigger: HarnessTrigger = (_, _, _) => {}
		val nopConfigure: HarnessConfigurer = _ => {
			case _ =>
		}
	}

}

class SpecActorFlowHarness(trigger: (SimActor, ActorRef, Long) => Unit, flow: SpecActorFlowHarness.Flow, name: String,
                           gw: Gateway,
                           testProbe: Option[ActorRef] = None,
                           configurer: SpecActorFlowHarness.HarnessConfigurer = h => {case _ => })
	extends SimActorImpl(name, gw) {
	import SpecActorFlowHarness._

	override def configure: Configuring = configurer(this)

	var started = false
	override def process(from: ActorRef, at: Long): Processing = {
		case KickOff() if !started =>
			log.debug("KickOff harness")
			trigger(this, from, at)
			started = true
		case a: Any if started =>
			assert(flow.isDefinedAt(this,from, at, a), s"Harness $name Cannot Handle: $a")
			log.debug(s"Harness processing Action: $a")
			flow.action(this, from, at).!.apply(a)
			if(testProbe isDefined) testProbe.get ! a
	}
}
