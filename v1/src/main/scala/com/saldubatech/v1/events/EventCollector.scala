/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.v1.events

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.saldubatech.v1.ddes.GlobalClock.{NoMoreWork, NotifyAdvance}

import scala.collection.mutable

object EventCollector {
	def props(spooler: EventSpooler) = Props(new EventCollector(spooler))

	def nullCollector = Props(new EventCollector(new EventSpooler{
		override protected def doFlush(traversableOnce: Seq[Event]): Unit = {}
		override def doClose: Unit = {}
	}))

	case class Report(station: String, event: Event)

	val records: mutable.Map[String,Long] = mutable.Map()
	val cache: mutable.Map[String, mutable.Map[String, LoadTiming]] = mutable.Map.empty

	class LoadTiming(ev: Event) {
		val load: String = ev.loadId
		val station: String = ev.stationId

		var arrive: Option[Long] = resolve(ev, OperationalEvent.New)
		var start: Option[Long] = resolve(ev, OperationalEvent.Start)
		var complete: Option[Long] = resolve(ev, OperationalEvent.Complete)
		var depart: Option[Long] = resolve(ev, OperationalEvent.Depart)

		def update(ev: Event): Unit = {
			arrive = resolve(ev, OperationalEvent.New)
			start = resolve(ev, OperationalEvent.Start)
			complete = resolve(ev, OperationalEvent.Complete)
			depart = resolve(ev, OperationalEvent.Depart)
		}

		def isComplete: Boolean = {
			Seq(arrive, start, complete, depart).forall(_.isDefined)
		}

		private def resolve(ev: Event, evType: OperationalEvent.CategorizedVal): Option[Long] = {
			if(evType == ev.evType) Some(ev.ts) else None
		}

		override def toString: String = {
			s"($station, $load, $arrive, $start, $complete, $depart)"
		}

	}

}

class EventCollector(_spooler: EventSpooler) extends Actor with ActorLogging {
	import EventCollector._
	import com.saldubatech.v1.ddes.Gateway.Shutdown

	val spooler: EventSpooler = _spooler
	val localCache: mutable.Map[String, LoadTiming] = mutable.Map.empty
	var totalRecords: Long = 0
	var batchRecords: Long = 0
	cache.put(self.path.name, localCache)
	records.put(self.path.name, 0)


	def cacheEvent(ev: Event): Unit = {
		val key = ev.loadId+"/"+ev.stationId
		if(cache.contains(key)) {
			val lt = localCache(key)
			lt.update(ev)
			if(lt isComplete) {
				cache remove key
				// Do something good (like spool it to a separate table)
			}
		} else {
			val lt = new LoadTiming(ev)
			localCache.put(key, lt)
		}
	}
	override def receive: Receive = {
		case Report(station, ev) =>
			log.debug(s"Recording Event at $station, ${ev.asTuple}")
			records.put(self.path.name, records(self.path.name)+1)
			totalRecords += 1
			batchRecords += 1
			spooler.record(ev)
			if(batchRecords > 100) {
				spooler.flush()
				batchRecords = 0
			}
		case NotifyAdvance(old, now) =>
			//log.debug(s"Flushing Event Collector at $now with $spooler")
			//spooler.flush
		case NoMoreWork(at) =>
			spooler.flush()
		case Shutdown() =>
			spooler.flush()
			self ! PoisonPill
	}

	override def postStop(): Unit = {
		spooler.flush
		spooler.close
		log.warning(s"${self.path.name} Collected ${records(self.path.name)} records")
		log.warning(s"${self.path.name} remaining incomplete operations: ${cache(self.path.name).size}")
		context.system.terminate()
	}
}
