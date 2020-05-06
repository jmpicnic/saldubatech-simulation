package com.saldubatech.units

import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.Simulation.{DomainSignal, SimRef}
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.protocols.Equipment
import com.saldubatech.transport.{Channel, MaterialLoad}
import com.saldubatech.units.Conveyance.{LoadAwareLiftToLoadAwareShuttle, LoadAwareShuttleToLoadAwareLift}
import com.saldubatech.units.carriage.{CarriageTravel, SlotLocator}
import com.saldubatech.units.unitsorter.UnitSorter

package object flowspec {

	object AddressBased {

		import com.saldubatech.units.lift.XSwitch
		import com.saldubatech.units.shuttle.Shuttle

		object ShuttleBuilder {
			def build[InductSourceSignal >: Equipment.ChannelSourceSignal <: DomainSignal, DischargeDestinatationSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
			(config: Shuttle.Configuration[InductSourceSignal, DischargeDestinatationSignal],
			 initialState: Shuttle.InitialState = Shuttle.InitialState(0, Map.empty))(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): SimRef = {
				val shuttleLevelProcessor = Shuttle.buildProcessor(config, initialState)
				actorCreator.spawn(shuttleLevelProcessor.init, config.name)
			}

			def configure(shuttle: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(shuttle, Shuttle.NoConfigure)
		}

		object LiftBuilder {

			def build[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
				OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
			(name: String, config: XSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
			(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator) =
				actorCreator.spawn(XSwitch.buildProcessor(name, config).init, name)

			def configure(lift: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, XSwitch.NoConfigure)
		}

		object UnitSorterBuilder {

			import com.saldubatech.units.unitsorter.UnitSorter

			def build(name: String, config: UnitSorter.Configuration)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): SimRef =
				actorCreator.spawn(UnitSorter.buildProcessor(name, config).init, name)

			def configure(lift: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, UnitSorter.NoConfigure)
		}

		class LiftShuttleChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, Equipment.XSwitchSignal, Equipment.ShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.ShuttleSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.ShuttleSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.ShuttleSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with Equipment.ShuttleSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.ShuttleSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.ShuttleSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
		}

		class SorterLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, Equipment.UnitSorterSignal, Equipment.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.XSwitchSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.XSwitchSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with Equipment.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.XSwitchSignal


			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal
		}

		class LiftSorterChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, Equipment.XSwitchSignal, Equipment.UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with Equipment.UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal
		}

		class ShuttleLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, Equipment.ShuttleSignal, Equipment.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.XSwitchSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.XSwitchSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.ShuttleSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with Equipment.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.XSwitchSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.ShuttleSignal
		}


		class InboundInductChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, Equipment.MockSourceSignal, Equipment.UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.MockSourceSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSourceSignal
		}

		class OutboundDischargeChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, Equipment.UnitSorterSignal, Equipment.MockSinkSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.MockSinkSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.MockSinkSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSinkSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.MockSinkSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.MockSinkSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.MockSinkSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.UnitSorterSignal
		}


		def buildAisle[InductSourceSignal >: Equipment.ChannelSourceSignal <: DomainSignal, DischargeSinkSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
		(name: String,
		 liftPhysics: CarriageTravel,
		 shuttlePhysics: CarriageTravel,
		 aisleDepth: Int,
		 align: Int,
		 inductChannel: (Int, Channel.Ops[MaterialLoad, InductSourceSignal, Equipment.XSwitchSignal]),
		 dischargeChannel: (Int, Channel.Ops[MaterialLoad, Equipment.XSwitchSignal, DischargeSinkSignal]),
		 shuttles: Seq[Int],
		 initialInventory: Map[Int, Map[SlotLocator, MaterialLoad]] = Map.empty)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): (SimRef, Seq[(Int, SimRef)]) = {
			val liftShuttles =
				shuttles.map { idx =>
					val inboundChannel = Channel.Ops(new LiftShuttleChannel(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_in"))
					val outboundChannel = Channel.Ops(new ShuttleLiftChannel(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_out"))
					val config = Shuttle.Configuration(s"shuttle_${name}_$idx", aisleDepth, shuttlePhysics, Seq(inboundChannel), Seq(outboundChannel))
					(ShuttleBuilder.build(config, initialInventory.get(idx).map(inv => Shuttle.InitialState(0, inv)).getOrElse(Shuttle.InitialState(0, Map.empty))), config.inbound.map(o => (idx, o)), config.outbound.map(o => (idx, o)))
				}
			val inboundDischarge = liftShuttles.flatMap {
				_._2
			}.toMap
			val outboundInduct = liftShuttles.flatMap {
				_._3
			}.toMap
			val liftConfig = XSwitch.Configuration(liftPhysics, Map(inductChannel), inboundDischarge, outboundInduct, Map(dischargeChannel), align)
			(LiftBuilder.build(name, liftConfig), liftShuttles.map(t => t._2.head._1 -> t._1))
		}
	}

	object LoadAware {
		import com.saldubatech.units.lift.LoadAwareXSwitch
		import com.saldubatech.units.shuttle.LoadAwareShuttle
		object ShuttleBuilder {
			def build[InductSourceSignal >: Equipment.ChannelSourceSignal <: DomainSignal, DischargeDestinatationSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
			(name: String, config: LoadAwareShuttle.Configuration[InductSourceSignal, DischargeDestinatationSignal],
			 initialState: LoadAwareShuttle.InitialState = LoadAwareShuttle.InitialState(0, Map.empty))(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): SimRef = {
				val shuttleLevelProcessor = LoadAwareShuttle.buildProcessor(name, config, initialState)
				actorCreator.spawn(shuttleLevelProcessor.init, name)
			}

			def configure(shuttle: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(shuttle, LoadAwareShuttle.NoConfigure)
		}

		object LiftBuilder {
			def build[InboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, InboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal,
				OutboundInductSignal >: Equipment.ChannelSourceSignal <: DomainSignal, OutboundDischargeSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
			(name: String, config: LoadAwareXSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
			(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator) =
				actorCreator.spawn(LoadAwareXSwitch.buildProcessor(name, config).init, name)

			def configure(lift: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, LoadAwareXSwitch.NoConfigure)
		}

		object UnitSorterBuilder {

			import com.saldubatech.units.unitsorter.UnitSorter

			def build(name: String, config: UnitSorter.Configuration)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): SimRef =
				actorCreator.spawn(UnitSorter.buildProcessor(name, config).init, name)

			def configure(lift: SimRef)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, UnitSorter.NoConfigure)
		}



		class InboundInductChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, Equipment.MockSourceSignal, Equipment.UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
		with UnitSorter.AfferentChannel {
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Equipment.MockSourceSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSourceSignal
		}

		class OutboundDischargeChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, Equipment.UnitSorterSignal, Equipment.MockSinkSignal](delay, deliveryTime, cards, configuredOpenSlots, name)
		with UnitSorter.EfferentChannel {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with Equipment.MockSinkSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with Equipment.MockSinkSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Equipment.MockSinkSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with Equipment.MockSinkSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Equipment.MockSinkSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Equipment.MockSinkSignal
		}


		def buildAisle[InductSourceSignal >: Equipment.ChannelSourceSignal <: DomainSignal, DischargeSinkSignal >: Equipment.ChannelSinkSignal <: DomainSignal]
		(name: String,
		 liftPhysics: CarriageTravel,
		 maxLiftCommands: Int,
		 shuttlePhysics: CarriageTravel,
		 maxShuttleCommands: Int,
		 aisleDepth: Int,
		 align: Int,
		 inductChannel: (Int, Channel.Ops[MaterialLoad, InductSourceSignal, Equipment.XSwitchSignal]),
		 dischargeChannel: (Int, Channel.Ops[MaterialLoad, Equipment.XSwitchSignal, DischargeSinkSignal]),
		 shuttles: Seq[Int],
		 initialInventory: Map[Int, Map[SlotLocator, MaterialLoad]] = Map.empty)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): (SimRef, Seq[(Int, SimRef)]) = {
			val liftShuttles =
				shuttles.map { idx =>
					val inboundChannel = Channel.Ops(new LoadAwareLiftToLoadAwareShuttle(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_in"))
					val outboundChannel = Channel.Ops(new LoadAwareShuttleToLoadAwareLift(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_out"))
					val config = LoadAwareShuttle.Configuration(aisleDepth, maxShuttleCommands, shuttlePhysics, Seq(inboundChannel), Seq(outboundChannel))
					(ShuttleBuilder.build(s"shuttle_${name}_$idx", config, initialInventory.get(idx).map(inv => LoadAwareShuttle.InitialState(0, inv)).getOrElse(LoadAwareShuttle.InitialState(0, Map.empty))), config.inbound.map(o => (idx, o)), config.outbound.map(o => (idx, o)))
				}
			val inboundDischarge = liftShuttles.flatMap {
				_._2
			}.toMap
			val outboundInduct = liftShuttles.flatMap {
				_._3
			}.toMap
			val liftConfig = LoadAwareXSwitch.Configuration(liftPhysics, maxLiftCommands, Map(inductChannel), inboundDischarge, outboundInduct, Map(dischargeChannel), align)
			(LiftBuilder.build(name, liftConfig), liftShuttles.map(t => t._2.head._1 -> t._1))
		}
	}

}
