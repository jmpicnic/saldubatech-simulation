/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.model.builders


import com.typesafe.scalalogging.Logger
import akka.actor.ActorRef
import com.saldubatech.base.{Identification, Material}
import com.saldubatech.base.channels.DirectedChannel
import com.saldubatech.model.builders.Builder.Token
import com.saldubatech.utils.Boxer._


object Builder {
	case class Token(name: String) extends Identification.Impl {
		override def toString: String = super.toString+s"::$name"
	}
	import scala.collection.mutable


	class Registry[T] {
	// Consider making this thread safe
		private val registry: mutable.Map[Token, Option[T]] = mutable.Map.empty[Token, Option[T]]
		private val registryIndex: mutable.Map[String, Token] = mutable.Map.empty[String, Token]

		override def toString =  registry.toString


		def reserve(name: String): Option[Token] = {
			val provisionalToken = Token(name)
			assert(!registry.contains(provisionalToken), s"Should not contain $provisionalToken in $registry")
			if (registry contains provisionalToken) None
			else {
				registry(provisionalToken) = None
				Some(provisionalToken)
			}
		}
		def release(tk: Token): Boolean = {
			if(registry contains tk) {
				registry -= tk
				true
			} else false
		}

		def register(token: Token, element: T): Option[Token] = {
			if (registry.contains(token) && registry(token).isEmpty) { // existing but not an active registration
				registry(token) = element.?
				registryIndex += (token.name -> token)
				Some(token)
			} else None
		}

		def register(name: String, element: T): Option[Token] = {
			val token = reserve(name)
			if (token isDefined) register(token.!, element) else None
		}
		def lookupByName(name: String): Option[T] = {
			val tk: Option[Token] = if(registryIndex contains name) registryIndex.get(name) else None
			if(tk isDefined) registry(tk.!) else None
		}
		def lookUp(tk: Token): Option[T] = {
			registry.get(tk).flatten
		}
	}
}

trait Builder {
	import Builder._
	protected val log = Logger(this.getClass.toString)
	protected val elementRegistry: Registry[ActorRef]
	protected val channelRegistry: Registry[DirectedChannel[Material]]


	var lastBuilt: List[Builder.Token] = List.empty

	def findElement(tk: Token): Option[ActorRef] = elementRegistry.lookUp(tk)
	def findElement(name: String): Option[ActorRef] = elementRegistry.lookupByName(name)
	def findChannel(tk: Token): Option[DirectedChannel[Material]] = channelRegistry.lookUp(tk)
	def findChannel(name: String): Option[DirectedChannel[Material]] = channelRegistry.lookupByName(name)
}
