/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.saldubatech.util.LogEnabled
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike}

import scala.collection.mutable

import scala.concurrent.duration._

object BaseSpec {

	class TestProbeExt[MsgType](probe: TestProbe[MsgType]) {
		def expectMessages(msgs: MsgType*) = {
			val remaining = mutable.ArrayBuffer[MsgType](msgs: _*)
			probe.fishForMessage(500 millis)(
				(msg: MsgType) =>
					if (remaining.contains(msg)) {
						remaining -= msg
						if(remaining isEmpty) FishingOutcome.Complete
						else FishingOutcome.Continue
					} else {
						if (msgs.contains(msg)) FishingOutcome.Fail(s"Repeated Message: $msg")
						else FishingOutcome.Fail(s"Unexpected Message: $msg")
					}
			)
		}
	}
	implicit def ExtendProbe[MsgType](probe: TestProbe[MsgType]): TestProbeExt[MsgType] = new TestProbeExt(probe)

}

trait BaseSpec
	extends WordSpec
		with Matchers
		with WordSpecLike
		with BeforeAndAfterAll
		with LogEnabled  {

	val name: String = this.getClass.getName+"_Spec"


	def unsupported: Nothing = {throw new UnsupportedOperationException()}

}
