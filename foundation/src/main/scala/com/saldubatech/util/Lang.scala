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
  implicit class DeOptionable[T](boxed: Option[T]) {
    def ! = boxed.get
  }

  implicit class PFMatcher[A](v: A) { // should be PFMatcher[-A]
    def matchTo[B](prtF: PartialFunction[A, B]): B = prtF(v) // Intellij Seems to flag this wrong (?)
  }

  def PF[A, B](pf: PartialFunction[A, B]): PartialFunction[A,B] = pf

}
