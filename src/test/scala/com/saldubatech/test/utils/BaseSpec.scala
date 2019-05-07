/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

trait BaseSpec extends WordSpecLike
    with Matchers
    with BeforeAndAfterAll {
  val name: String = this.getClass.getName+"_Spec"

  val specLogger: Logger = Logger(s"com.saldubatech.$name")

  def unsupported: Nothing = {throw new UnsupportedOperationException()}


}