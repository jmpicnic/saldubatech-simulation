/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.util

object Lang {
  val ZERO_TOLERANCE = 1e-6

  type TBD
  def TBD[T]: T = {null.asInstanceOf[T]}

  implicit class Optionable[T](naked: T) {
    def ? = Some(naked)
  }
  implicit class Deoptionable[T](boxed: Option[T]) {
    def ! = boxed.get
  }
}
