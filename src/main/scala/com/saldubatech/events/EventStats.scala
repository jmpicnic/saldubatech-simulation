/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.events

object EventStats{

	class Accumulator {
		var count: Int = 0
		var sum: Long = 0
		var sumSq: Long = 0
		def add(value: Long): Unit = {count += 1;sum += value; sumSq += value*value}
		def basicStats(): (Int, Double, Double) = (count, sum.toDouble/count.toDouble, sumSq.toDouble/count.toDouble - (sum*sum).toDouble)
	}
}

class EventStats {/*
	import Operational._
	import EventStats._
	val cycleCache: mutable.Map[(String,Material,Event.Value), (Long)] = mutable.Map()
	val cycleStore: mutable.Map[(String,Event.Value), Accumulator] = mutable.Map()
	private val afterCache: mutable.Map[(String,Material,Event.Value), (Long)] = mutable.Map()
	private val beforeCache: mutable.Map[(String,Material,Event.Value), (Long)] = mutable.Map()
	private val delayStore: mutable.Map[(String,Event.Value), Accumulator] = mutable.Map()

	def record(ev: Operational): Unit = {
		val complement_ev = complementary(ev.evType)
		if(cycleCache contains (ev.station, ev.load, complement_ev)) {
			val other = cycleCache((ev.station, ev.load, complement_ev))
			val duration = ev.at - other
			val marker = if(duration > 0 || (before contains ev.evType)) ev.evType else complement_ev
			val key = (ev.station, marker)
			if(!(cycleStore contains key)) cycleStore.put(key, new Accumulator())
			cycleStore(key).add(duration)
			cycleCache.remove((ev.station, ev.load, complement_ev))
		} else {
			cycleCache.put((ev.station, ev.load, ev.evType), ev.at)
		}
		if(before contains ev.evType) {
			val before_ev = before(ev.evType)
			if (afterCache contains(ev.station, ev.load, before_ev)) {
				val duration = ev.at - afterCache((ev.station, ev.load, before_ev))
				val key = (ev.station, ev.evType)
				if (!(delayStore contains key)) delayStore.put(key, new Accumulator())
				delayStore(key).add(duration)
				afterCache.remove((ev.station, ev.load, before_ev))
			} else {
				beforeCache.put((ev.station, ev.load, ev.evType), ev.at)
			}
		}
		if(after contains ev.evType) {
			val after_ev = after(ev.evType)
			if (beforeCache contains(ev.station, ev.load, after_ev)) {
				val duration = beforeCache((ev.station, ev.load, after_ev)) - ev.at
				val key = (ev.station, after_ev)
				if (!(delayStore contains key)) delayStore.put(key, new Accumulator())
				delayStore(key).add(duration)
				beforeCache.remove((ev.station, ev.load, after_ev))
			} else {
				afterCache.put((ev.station, ev.load, ev.evType), ev.at)
			}
		}
	}

	def basicStats(station: String, event: Event.Value) =
		(cycleStore((station, event)).basicStats(), if(delayStore contains (station, event)) delayStore((station, event)).basicStats() else None)
*/
}
