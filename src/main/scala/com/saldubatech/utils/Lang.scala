/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.utils

object Lang {
  val ZERO_TOLERANCE = 1e-6

  def TBD[T]: T = {null.asInstanceOf[T]}

  implicit class Crossable[T1](xs: Traversable[T1]) {
    def X[T2](ys: Traversable[T2]): Traversable[(T1, T2)] = for(x <- xs; y <- ys) yield (x, y)
  }

}
