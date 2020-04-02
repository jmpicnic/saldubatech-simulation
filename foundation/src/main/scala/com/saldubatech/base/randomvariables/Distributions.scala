/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.randomvariables

import org.apache.commons.math3.distribution.{ExponentialDistribution, GeometricDistribution}



object Distributions {

  type DoubleRVar = () => Double
  type LongRVar = () => Long
  final val zeroLong: LongRVar = () => 0
  final val zeroDouble: DoubleRVar = () => 0.0

  implicit def toLong(dVar: DoubleRVar): LongRVar = {() => math.round(dVar())}

  def exponential(mean: Double): DoubleRVar = new ExponentialDistribution(mean).sample

  def geometric(p: Double): LongRVar = new GeometricDistribution(p).sample

  def discreteExponential(l: Double): LongRVar = //() => Math.round(exponential(l)())
  {
    // p = 1 − e−λ
    val p = 1.0 - Math.exp(-1.0/l)
    geometric(p)
  }

  def scaled(rvar: DoubleRVar, scale: Double, shift: Double): () => Double = () => scale * rvar()  + shift
  def scaled(rvar: LongRVar, scale: Long, shift: Long): () => Long = () => scale * rvar()  + shift

}
