/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.base.resource

import com.saldubatech.base.Identification
import com.saldubatech.v1.base.resource.Use.DiscreteUsable

object ResourcePool {
	def apply[R](resources: Map[String, R]) = new ResourcePool[R](resources)
}
class ResourcePool[R](resources: Map[String, R]) extends Identification.Impl with DiscreteUsable[R] {
	val items: Map[String, R] = resources

}
