/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.ActorRef

object SimDSL {

  class Delayer(target: ActorRef, msg: Any)(implicit host: SimActorMixIn) {
    def in(timing: (Long, Long)): Unit = { // now, delay
      host.tellTo(target, msg, timing._1, timing._2)
    }
    def now(at: Long): Unit = {
      host.tellTo(target, msg, at)
    }
  }
	class Addresser(msg: Any)(implicit host: SimActorMixIn) {
    def ~> : ActorRef => Delayer = (target: ActorRef) => {
      new Delayer(target, msg)
    }
  }

	implicit def anyToAddresser(a: Any)(implicit host: SimActorMixIn): Addresser = new Addresser(a)

}
