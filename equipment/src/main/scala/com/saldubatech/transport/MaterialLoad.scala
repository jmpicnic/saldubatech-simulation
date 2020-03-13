/*
 * Copyright (c) 2020. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.transport

import com.saldubatech.base.Identification

case class MaterialLoad(lid: String = java.util.UUID.randomUUID.toString) extends Identification.Impl(lid)
