/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base.resource

import java.util.UUID

import com.typesafe.scalalogging.Logger

import scala.collection.mutable

object DiscreteResourceBox {
  def apply[T](name: String, assets: Set[String]) =
    new DiscreteResourceBox(name, assets)

  def apply(name: String, capacity: Int) =
    new DiscreteResourceBox(name,
      Set[String]() ++ (1 to capacity).map(_ => UUID.randomUUID().toString))

  def apply(orig: DiscreteResourceBox) =
    new DiscreteResourceBox(orig.name, orig.sourceAssets)

}

class DiscreteResourceBox(val name: String, assets: Set[String]) {
  //private val allSlots = mutable.Set[String]() ++= (1 to capacity).map(_ => UUID.randomUUID().toString)
  private val initialAssets = assets.map(item => item)
  private var remainingAssets = mutable.Set[String]() ++= initialAssets
  private val logger = Logger(this.getClass.getName)

  def sourceAssets: Set[String] =  Set[String]() ++ assets

  def isFull: Boolean = remainingAssets.size == initialAssets.size
  def isEmpty: Boolean = remainingAssets isEmpty

  def available(): Int = {
    remainingAssets size
  }

  def isAvailable(resource: String): Boolean = {
    remainingAssets contains resource
  }

  def checkoutOne(): Option[String] = {
    if(remainingAssets isEmpty) None
    else {
      val h = remainingAssets.head
      remainingAssets -= h
      logger.debug(s"$name Checking out resource: $h, remaining ${remainingAssets.size}")
      Some(h)
    }
  }


  def reserve(slot: String): Boolean = {
    if(remainingAssets contains slot) {
      remainingAssets -= slot
      logger.debug(s"$name Checking out resource: $slot, remaining ${remainingAssets.size}")
      true
    } else false
  }


  def checkin(item: String): Unit = {
    if (!(initialAssets contains item))
      throw new IllegalArgumentException("The item <"+item+"> does not belong in this box: "+name)
    if (remainingAssets contains item)
      throw new IllegalArgumentException("Tried to check-in a not checked out element: "+item+" into "+name)
    remainingAssets += item
    logger.debug(s"$name Checking in resource: $item, remaining ${remainingAssets.size}")
  }

  def reset(): Unit = {
    remainingAssets = mutable.Set() ++= initialAssets
  }

  override def toString: String = name
}
