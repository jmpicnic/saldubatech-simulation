/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.model.configuration

import com.saldubatech.base.Identification
import com.saldubatech.v1.model.configuration.ShuttleStorage.ShuttleStore
import com.saldubatech.v1.model.configuration.Sorting.CircularSorter

object GTP {

	/**
		*
		* @param id
		* @param store
		* @param sorter
		* @param layout
		* @param storeEndpointNames: Map from External Endpoint Name (used by layout) and intrinsic naming
		*                              in the ShuttleStore. If not present, the Layout needs to use the intrinsic
		*                              names of the Shuttle Store.
		*/
	case class SortedStore(id: String,
	                       store: ShuttleStore,
	                       sorter: CircularSorter,
	                       layout: Layout,
	                       storeEndpointNames: Option[Map[String, String]] = None
	                      ) extends Identification.Impl(id) {
		val reverseStoreEndpointNames = if(storeEndpointNames isDefined) Some(storeEndpointNames.head.map{ case (k, v) => v -> k}) else None
	}

}
