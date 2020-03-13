/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.ActorRef

object DeclarativeUseCaseRunner {
  class ProtocolStep(val peer:ActorRef, val msg:Any){}
  case class DoSend(to: ActorRef, send: Any) extends ProtocolStep(to, send)
  case class DoReceive(from: ActorRef, receive: Any) extends ProtocolStep(from, receive)
}


trait DeclarativeUseCaseRunner {
  import DeclarativeUseCaseRunner._
  def expectMsg[T](msg: T): T
  def lastSender: ActorRef

  def run(protocol: Seq[DeclarativeUseCaseRunner.ProtocolStep]): Unit = {
    for(step <- protocol) {
      step match {
        case DoSend(to, sendMsg) =>
          to ! sendMsg
        case DoReceive(from, receiveMsg) =>
          expectMsg(receiveMsg)
          assert(lastSender == from, "Sender "+lastSender+" does not match expected one: "+from)
      }

    }
  }

}
