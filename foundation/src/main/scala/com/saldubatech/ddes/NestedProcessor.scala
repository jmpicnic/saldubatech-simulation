/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.ddes

import akka.actor.typed.{ActorRef, Behavior}
import com.saldubatech.ddes.Clock.ClockMessage
import com.saldubatech.ddes.Processor.{ProcessorMessage, ProcessorRef}

object NestedProcessor {

	case class ProcessorContext[DomainContext](processor: ProcessorRef, sourceBehavior: Behavior[ProcessorMessage],
	                            dispatch: PartialFunction[Option[ProcessorMessage], Behavior[ProcessorMessage]], domainPayload: DomainContext)

	trait ParentProcessor[DomainMessage, DomainContext] {
		def push[Result](child: NestedProcessor[DomainMessage, DomainContext, Result], ctx: DomainContext) = {
			child.init
		}
	}

}

abstract class NestedProcessor[DomainMessage, DomainContext, Result](parentContext: NestedProcessor.ProcessorContext[DomainContext]) {
	import NestedProcessor._

	def init: Behavior[ProcessorMessage]

	protected def pop(signal: Option[ProcessorMessage]): (Behavior[ProcessorMessage], Result) =
		parentContext.dispatch(signal) -> doneResult

	protected def doneResult: Result
}
