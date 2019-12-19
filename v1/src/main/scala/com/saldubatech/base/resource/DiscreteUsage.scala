/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.resource

class DiscreteUsage(capacity: Int) {
  assert(capacity > 0)
  private var _usage: Int = 0

  def acquire(quantity: Int): Boolean = {
    val newUsage = _usage+quantity
    if (newUsage > capacity) {
      // Over capacity, or q = 0 do nothing
      false
    } else {
      _usage = newUsage
      true
    } 
  }

  def release(quantity: Int): Boolean = {
    if(quantity > _usage) {
      false // release more than in use --> Do nothing
    } else {
      _usage -= quantity
      true
    }
  }

  def available: Int = capacity - _usage
}
