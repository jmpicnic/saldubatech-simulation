package com.saldubatech.protocols

import com.saldubatech.base.Identification

case class MaterialLoad(lid: String = java.util.UUID.randomUUID.toString) extends Identification.Impl(lid)
