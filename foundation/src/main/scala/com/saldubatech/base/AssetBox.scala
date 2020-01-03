/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import com.saldubatech.util.LogEnabled

import scala.collection.mutable

object AssetBox {
	def ofStrings(capacity: Int, name: String = java.util.UUID.randomUUID.toString): AssetBox[String] = AssetBox[String]((1 to capacity).map(_ => java.util.UUID.randomUUID().toString).toSet, name)
}

case class AssetBox[ASSET](initialAssets: Set[ASSET], name: String)
	extends Identification.Impl(name) with LogEnabled {
	private val balance: mutable.Set[ASSET] = mutable.Set(initialAssets.toSeq: _*)

	def isFull = balance.size == initialAssets.size
	def isEmpty = balance.isEmpty
	def available = balance.size
	def isAvailable(a: ASSET) = balance.contains(a)

	def checkoutAny: Option[ASSET] = {
		val asset = balance.headOption.filter(balance.remove)
		log.debug(s"CheckoutAny: $asset, Balance: $balance")
		asset
	}

	def checkout(asset: ASSET): Option[ASSET] = {
		log.debug(s"Checkingout asset $asset")
		Some(asset).filter(balance.remove)
	}

	def checkin(a: ASSET): Unit =
		if(initialAssets.contains(a)) {if(!balance.add(a)) throw new IllegalStateException(s"Asset $a is not checked-out, cannot checkin into box $name")}
		else {throw new IllegalArgumentException(s"Asset $a is not part of box $name with allowed contents $initialAssets")}

	def reset(): Unit = balance ++= initialAssets

}
