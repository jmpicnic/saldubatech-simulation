/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.ddes.{SimMessage, Subject}

object Processor {
case class Task[C <: ExecutionCommand, R <: ExecutionResource]
	(cmd: C, materials: Map[Material, DirectedChannel.Endpoint[Material]], resource: Option[R] = None)(implicit createdAt: Long)
		extends Identification.Impl
	case class __Task[C <: ExecutionCommand, V <: DirectedChannel.Endpoint[Material]](cmd: C, materials: Map[Material, V], createdAt: Long)
		extends Identification.Impl

	trait ExecutionResource extends Identification

	class ExecutionResourceImpl(_id: String = java.util.UUID.randomUUID().toString) extends
		Identification.Impl(_id) with ExecutionResource

	case class ConfigureOwner(newOwner: ActorRef)

	trait ExecutionCommand extends Identification

	trait ExecutionNotification extends Subject.Notification

	class ExecutionCommandImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends SimMessage.Impl(_id) with ExecutionCommand

	class ExecutionNotificationImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends Subject.NotificationImpl(_id) with ExecutionNotification

	case class ReceiveLoad(via: DirectedChannel.End[Material], load: Material) extends ExecutionNotificationImpl

	case class StageLoad(sourceCommandId: String, material: Option[Material]) extends ExecutionNotificationImpl

	case class StartTask(sourceCmdId: String, materials: Seq[Material]) extends ExecutionNotificationImpl

	case class CompleteTask(sourceCommandId: String, materials: Seq[Material] = Seq.empty, results: Seq[Material] = Seq.empty) extends ExecutionNotificationImpl

	case class DeliverResult(sourceCommandId: String, via: DirectedChannel.Start[Material], result: Material) extends ExecutionNotificationImpl


	trait ExecutionObserver extends Subject.Observer[ExecutionNotification] {
		override def acceptNotification(msg: ExecutionNotification, from: ActorRef)(implicit at: Long): Unit = {
			msg match {
				case cmd: ReceiveLoad => receiveLoad(from, cmd.via, cmd.load)
				case cmd: StartTask => startProcessing(from, cmd.sourceCmdId, cmd.materials)
				case cmd: StageLoad => stageLoad(from, cmd.sourceCommandId, cmd.material)
				case cmd: CompleteTask => completeTask(from, cmd.sourceCommandId, cmd.materials, cmd.results)
				case cmd: DeliverResult => deliverResult(from, cmd.sourceCommandId, cmd.via, cmd.result)
			}
		}

		def receiveLoad(from: ActorRef, via: DirectedChannel.End[Material], load: Material)(implicit at: Long): Unit

		def startProcessing(from: ActorRef, sourceCmdId: String, materials: Seq[Material])(implicit at: Long)

		def stageLoad(from: ActorRef, sourceCmdId: String, material: Option[Material])(implicit at: Long)

		def completeTask(from: ActorRef, sourceCommandId: String, materials: Seq[Material], results: Seq[Material])

		def deliverResult(from: ActorRef, sourceCommandId: String, via: DirectedChannel.Start[Material], result: Material)
	}

}
