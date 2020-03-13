/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.equipment.elements

import com.saldubatech.v1.base.Material

object ExecutionProxy {

  trait ExecutionObserver {
    def onCompleteStaging(commandId: String, load: Option[Material], at: Long): Unit
    def onCompleteCommand(commandId: String, load: Option[Material], result: Option[Material], at: Long): Unit
  }
}


trait ExecutionProxy[C <: ProcessingCommand] {
	def executeJob(cmd: C, at: Long, load: Option[Material] = None): Boolean
}
