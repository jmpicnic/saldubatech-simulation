/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.equipment.elements

import akka.actor.ActorRef
import com.saldubatech.ddes.SimMessage
import com.saldubatech.resource.DiscreteResourceBox

object InductDischargeProtocol {
  // Discharge --> Induct :: Configuration
  final case class Connect(from: ActorRef, entryPointName: String)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
  final case class Disconnect(from: ActorRef, entryPointName: String)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)

  // Induct --> Discharge
  final case class Allocate(box: DiscreteResourceBox)
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)
  final case class Restore(boxName: String, token: String, load: PhysicalJob[ProcessingCommand])
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)

  // Discharge --> Induct :: Operation
  final case class Accept[T <: PhysicalJob[ProcessingCommand]](load: T) // Should move to a more general "equipmentActor" class
    extends SimMessage.Impl(java.util.UUID.randomUUID().toString)

}
