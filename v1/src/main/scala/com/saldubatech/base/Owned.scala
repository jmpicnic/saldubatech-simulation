/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.base

import akka.actor.ActorRef
import com.saldubatech.ddes.Subject
import com.saldubatech.util.Lang._

trait Owned[N <: Subject.Notification] extends Subject[N] {
	private var _owner: Option[ActorRef] = None
	protected def owner: ActorRef = _owner.!

	protected def configureOwner(owner: ActorRef) {
		assert(_owner isEmpty, "Cannot Configure owner twice")
		_owner = owner.?
		registerObserver(owner)
	}
}
