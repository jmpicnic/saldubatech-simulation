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
import com.saldubatech.v1.ddes.SimActorImpl.Configuring
import com.saldubatech.v1.ddes.SimActor.Processing
import com.saldubatech.v1.ddes.{Gateway, SimActorImpl, SimActor}

object SpecActorHarness {
	def simActor(trigger: HarnessTrigger,
	             actions: Seq[HarnessStep],
	             name: String,
	             gw: Gateway,
	             testProbe: Option[ActorRef] = None,
	             configurer: HarnessConfigurer = h => {case _ => }): ActorRef =
		gw.simActorOf(Props(new SpecActorHarness(trigger, actions, name, gw, testProbe, configurer)), name)

	case class KickOff()

	type HarnessTrigger = (SimActor, ActorRef, Long) => Unit
	type HarnessStep = (SimActor, ActorRef, Long) => Processing
	type HarnessConfigurer = SpecActorHarness => Configuring

	def nopStep(msg: String="Step: "): HarnessStep = (host, _, at) => { case a: Any => host.log.info(s"###### $msg $a at $at")}
	val nopTrigger: HarnessTrigger = (_, _, _) => {}
	val nopConfigure: HarnessConfigurer = _ => {case _ => }
}

class SpecActorHarness(trigger: SpecActorHarness.HarnessTrigger,
                       actions: Seq[SpecActorHarness.HarnessStep],
                       name: String,
                       gw: Gateway,
                       testProbe: Option[ActorRef] = None,
                       configurer: SpecActorHarness.HarnessConfigurer = h => {case _ => }) extends SimActorImpl(name, gw) {
	import SpecActorHarness._

	override def configure: Configuring = configurer(this)

	private var idx: Int = -1

	override def process(from: ActorRef, at: Long): Processing = {
		case KickOff() if idx == -1 =>
			log.debug("KickOff harness")
			trigger(this, from, at)
			idx = 0
		case a: Any =>
			assert(actions.length > idx, s"Harness $name out of scope action: $a, step $idx with Actions: ${actions.size}")
			if(actions.size > idx) {
				log.debug(s"Harness processing Action $idx: $a")
				actions(idx)(this, from, at).applyOrElse(a,
          (msg: Any) ⇒ {
            log.error(s"Unknown Action Received <$msg> in Harness $name from ${from.path.name} for step $idx")
            new Object
          })
				idx += 1
			}
			if(testProbe isDefined) testProbe.get ! a
	}
}
