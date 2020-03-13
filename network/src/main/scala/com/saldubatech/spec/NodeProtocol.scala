/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.spec

import com.saldubatech.v1.base.Material
import com.saldubatech.v1.base.channels.DirectedChannel
import com.saldubatech.spec.ProcessorProtocol.Job
import com.saldubatech.util.Implicits._

object NodeProtocol {
	// Demand
	case class DemandRequest(job: Job, destination: DirectedChannel[Material])
	case class DemandDecline(request: DemandRequest)

	// Fulfillment
	case class FulfillmentDelivery(request: DemandRequest, complete: Boolean)

}
