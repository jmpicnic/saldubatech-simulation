/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.randomVariables

import com.saldubatech.randomvariables.Distributions._
import com.saldubatech.test.BaseSpec


class DistributionsSpec extends BaseSpec {
	val allowedError = 3.0
	"An Exponential Distribution" when {
		val mean = 200.00
		val exp: DoubleRVar= exponential(mean)
		"sampled 10000 times" must {
			var acc: Double = 0
			var count: Int = 0
			for (i <- 1 to 100000) {acc += exp(); count += 1}
			val avg = acc / count.toDouble
			"get an average that approximates the mean" in {
				log.debug(s"Obtained Avg: $avg, Count: $count")
				avg shouldBe(mean +- allowedError)
			}
		}
	}
	"A Discrete Exponential Distribution" when {
		val mean = 200.00
		val dExp: LongRVar= discreteExponential(mean)
		"sampled 10000 times" must {
			var acc: Long = 0
			var acc2: Long = 0
			var count: Int = 0
			for (i <- 1 to 100000) {val v = dExp(); acc += v; acc2 += v*v; count += 1}
			val avg = acc.toDouble / count.toDouble
			val std = Math.sqrt(acc2.toDouble/count.toDouble - avg*avg)
			"get an average that approximates the mean" in {
				log.debug(s"Obtained Avg: $avg, Count: $count")
				avg shouldBe(mean +- allowedError)
				std shouldBe(mean +- allowedError)
			}
		}
	}

}
