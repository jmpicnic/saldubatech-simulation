/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.processor

import akka.actor.ActorRef
import com.saldubatech.base.Material
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.ddes.Subject

object Processor{

	case class ConfigureOwner(newOwner: ActorRef)

	trait ExecutionNotification extends Subject.Notification
	class ExecutionNotificationImpl(_id: String = java.util.UUID.randomUUID().toString)
		extends Subject.NotificationImpl(_id) with ExecutionNotification

	case class ReceiveLoad[M <: Material](via: DirectedChannel.End[M], load: M) extends ExecutionNotificationImpl
	case class StageLoad(sourceCommandId: String, material: Option[Material]) extends ExecutionNotificationImpl
	case class StartTask(sourceCmdId: String, materials: Seq[Material]) extends ExecutionNotificationImpl
	case class CompleteTask(sourceCommandId: String, materials: Seq[Material] = Seq.empty, results: Seq[Material] = Seq.empty) extends ExecutionNotificationImpl
	case class DeliverResult[M <: Material](sourceCommandId: String, via: DirectedChannel.Start[M], result: M) extends ExecutionNotificationImpl


	trait ExecutionObserver[M <: Material] extends Subject.Observer[ExecutionNotification] {
		override def acceptNotification(msg: ExecutionNotification, from: ActorRef)(implicit at: Long): Unit = {
			msg match {
				case cmd: ReceiveLoad[M] => receiveLoad(from, cmd.via, cmd.load)
				case cmd: StartTask => startTask(from, cmd.sourceCmdId, cmd.materials)
				case cmd: StageLoad => stageLoad(from, cmd.sourceCommandId, cmd.material)
				case cmd: CompleteTask => completeTask(from, cmd.sourceCommandId, cmd.materials, cmd.results)
				case cmd: DeliverResult[M] => deliverResult(from, cmd.sourceCommandId, cmd.via, cmd.result)
			}
		}

		def receiveLoad(from: ActorRef, via: DirectedChannel.End[M], load: M)(implicit at: Long): Unit

		def startTask(from: ActorRef, sourceCmdId: String, materials: Seq[Material])(implicit at: Long)

		def stageLoad(from: ActorRef, sourceCmdId: String, material: Option[Material])(implicit at: Long)

		def completeTask(from: ActorRef, sourceCommandId: String, materials: Seq[Material], results: Seq[Material])

		def deliverResult(from: ActorRef, sourceCommandId: String, via: DirectedChannel.Start[M], result: M)
	}

}
