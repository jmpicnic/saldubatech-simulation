package com.saldubatech.units

import com.saldubatech.ddes.Clock.Delay
import com.saldubatech.ddes.{Clock, Processor, SimulationController}
import com.saldubatech.transport.{Channel, ChannelConnections, MaterialLoad}
import com.saldubatech.units.carriage.{CarriageTravel, SlotLocator}
import com.saldubatech.units.unitsorter.UnitSorterSignal

package object flowspec {

	object AddressBased {

		import com.saldubatech.units.lift.XSwitch
		import com.saldubatech.units.shuttle.Shuttle

		object ShuttleBuilder {
			def build[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeDestinatationSignal >: ChannelConnections.ChannelDestinationMessage]
			(config: Shuttle.Configuration[InductSourceSignal, DischargeDestinatationSignal],
			 initialState: Shuttle.InitialState = Shuttle.InitialState(0, Map.empty))(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref = {
				val shuttleLevelProcessor = Shuttle.buildProcessor(config, initialState)
				actorCreator.spawn(shuttleLevelProcessor.init, config.name)
			}

			def configure(shuttle: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(shuttle, Shuttle.NoConfigure)
		}

		object LiftBuilder {

			def build[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
				OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
			(name: String, config: XSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
			(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator) =
				actorCreator.spawn(XSwitch.buildProcessor(name, config).init, name)

			def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, XSwitch.NoConfigure)
		}

		object UnitSorterBuilder {

			import com.saldubatech.units.unitsorter.UnitSorter

			def build(name: String, config: UnitSorter.Configuration)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref =
				actorCreator.spawn(UnitSorter.buildProcessor(name, config).init, name)

			def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, UnitSorter.NoConfigure)
		}

		class LiftShuttleChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, XSwitch.XSwitchSignal, Shuttle.ShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with Shuttle.ShuttleSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with Shuttle.ShuttleSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with XSwitch.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with Shuttle.ShuttleSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with Shuttle.ShuttleSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with Shuttle.ShuttleSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal
		}

		class SorterLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, UnitSorterSignal, XSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with XSwitch.XSwitchSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with XSwitch.XSwitchSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with XSwitch.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with XSwitch.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with XSwitch.XSwitchSignal


			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
		}

		class LiftSorterChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, XSwitch.XSwitchSignal, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with XSwitch.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal
		}

		class ShuttleLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, Shuttle.ShuttleSignal, XSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with XSwitch.XSwitchSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with XSwitch.XSwitchSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with Shuttle.ShuttleSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with XSwitch.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with XSwitch.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with XSwitch.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with XSwitch.XSwitchSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with Shuttle.ShuttleSignal
		}


		class InboundInductChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
		}

		class OutboundDischargeChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
			type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with ChannelConnections.DummySinkMessageType

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with ChannelConnections.DummySinkMessageType

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with ChannelConnections.DummySinkMessageType

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
		}


		def buildAisle[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeSinkSignal >: ChannelConnections.ChannelDestinationMessage]
		(name: String,
		 liftPhysics: CarriageTravel,
		 shuttlePhysics: CarriageTravel,
		 aisleDepth: Int,
		 align: Int,
		 inductChannel: (Int, Channel.Ops[MaterialLoad, InductSourceSignal, XSwitch.XSwitchSignal]),
		 dischargeChannel: (Int, Channel.Ops[MaterialLoad, XSwitch.XSwitchSignal, DischargeSinkSignal]),
		 shuttles: Seq[Int],
		 initialInventory: Map[Int, Map[SlotLocator, MaterialLoad]] = Map.empty)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): (Processor.Ref, Seq[(Int, Processor.Ref)]) = {
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
			def build[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeDestinatationSignal >: ChannelConnections.ChannelDestinationMessage]
			(name: String, config: LoadAwareShuttle.Configuration[InductSourceSignal, DischargeDestinatationSignal],
			 initialState: LoadAwareShuttle.InitialState = LoadAwareShuttle.InitialState(0, Map.empty))(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref = {
				val shuttleLevelProcessor = LoadAwareShuttle.buildProcessor(name, config, initialState)
				actorCreator.spawn(shuttleLevelProcessor.init, name)
			}

			def configure(shuttle: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(shuttle, LoadAwareShuttle.NoConfigure)
		}

		object LiftBuilder {
			def build[InboundInductSignal >: ChannelConnections.ChannelSourceMessage, InboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage,
				OutboundInductSignal >: ChannelConnections.ChannelSourceMessage, OutboundDischargeSignal >: ChannelConnections.ChannelDestinationMessage]
			(name: String, config: LoadAwareXSwitch.Configuration[InboundInductSignal, InboundDischargeSignal, OutboundInductSignal, OutboundDischargeSignal])
			(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator) =
				actorCreator.spawn(LoadAwareXSwitch.buildProcessor(name, config).init, name)

			def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, LoadAwareXSwitch.NoConfigure)
		}

		object UnitSorterBuilder {

			import com.saldubatech.units.unitsorter.UnitSorter

			def build(name: String, config: UnitSorter.Configuration)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): Processor.Ref =
				actorCreator.spawn(UnitSorter.buildProcessor(name, config).init, name)

			def configure(lift: Processor.Ref)(implicit ctx: Processor.SignallingContext[_]): Unit = ctx.signal(lift, UnitSorter.NoConfigure)
		}

		class LiftShuttleChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, LoadAwareXSwitch.XSwitchSignal, LoadAwareShuttle.LoadAwareShuttleSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with LoadAwareShuttle.LoadAwareShuttleSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with LoadAwareShuttle.LoadAwareShuttleSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareShuttle.LoadAwareShuttleSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with LoadAwareShuttle.LoadAwareShuttleSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with LoadAwareShuttle.LoadAwareShuttleSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with LoadAwareShuttle.LoadAwareShuttleSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareXSwitch.XSwitchSignal
		}

		class SorterLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, UnitSorterSignal, LoadAwareXSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			override type TransferSignal = Channel.TransferLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal
			override type PullSignal = Channel.PulledLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal
			override type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareXSwitch.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with LoadAwareXSwitch.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with LoadAwareXSwitch.XSwitchSignal


			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
		}

		class LiftSorterChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, LoadAwareXSwitch.XSwitchSignal, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareXSwitch.XSwitchSignal
		}

		class ShuttleLiftChannel(override val delay: () => Option[Delay], override val deliveryTime: () => Option[Delay], override val cards: Set[String], override val configuredOpenSlots: Int = 1, override val name: String)
			extends Channel[MaterialLoad, LoadAwareShuttle.LoadAwareShuttleSignal, LoadAwareXSwitch.XSwitchSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with LoadAwareShuttle.LoadAwareShuttleSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal =
				new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareXSwitch.XSwitchSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): Channel.PulledLoad[MaterialLoad] with PullSignal =
				new Channel.PulledLoadImpl(ld, card, idx, this.name) with LoadAwareXSwitch.XSwitchSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with LoadAwareXSwitch.XSwitchSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with LoadAwareXSwitch.XSwitchSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal =
				new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with LoadAwareShuttle.LoadAwareShuttleSignal
		}


		class InboundInductChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, ChannelConnections.DummySourceMessageType, UnitSorterSignal](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with UnitSorterSignal
			type PullSignal = Channel.PulledLoad[MaterialLoad] with UnitSorterSignal
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with ChannelConnections.DummySourceMessageType

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with UnitSorterSignal

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with UnitSorterSignal

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with UnitSorterSignal

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySourceMessageType
		}

		class OutboundDischargeChannel(delay: () => Option[Delay], deliveryTime: () => Option[Delay], cards: Set[String], configuredOpenSlots: Int = 1, name: String = java.util.UUID.randomUUID().toString)
			extends Channel[MaterialLoad, UnitSorterSignal, ChannelConnections.DummySinkMessageType](delay, deliveryTime, cards, configuredOpenSlots, name) {
			type TransferSignal = Channel.TransferLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
			type PullSignal = Channel.PulledLoad[MaterialLoad] with ChannelConnections.DummySinkMessageType
			type AckSignal = Channel.AcknowledgeLoad[MaterialLoad] with UnitSorterSignal

			override def transferBuilder(channel: String, load: MaterialLoad, resource: String): TransferSignal = new Channel.TransferLoadImpl[MaterialLoad](channel, load, resource) with ChannelConnections.DummySinkMessageType

			override def loadPullBuilder(ld: MaterialLoad, card: String, idx: Int): PullSignal = new Channel.PulledLoadImpl[MaterialLoad](ld, card, idx, this.name) with ChannelConnections.DummySinkMessageType

			override type DeliverSignal = Channel.DeliverLoadImpl[MaterialLoad] with ChannelConnections.DummySinkMessageType

			override def deliverBuilder(channel: String): DeliverSignal = new Channel.DeliverLoadImpl[MaterialLoad](channel) with ChannelConnections.DummySinkMessageType

			override def acknowledgeBuilder(channel: String, load: MaterialLoad, resource: String): AckSignal = new Channel.AckLoadImpl[MaterialLoad](channel, load, resource) with UnitSorterSignal
		}


		def buildAisle[InductSourceSignal >: ChannelConnections.ChannelSourceMessage, DischargeSinkSignal >: ChannelConnections.ChannelDestinationMessage]
		(name: String,
		 liftPhysics: CarriageTravel,
		 maxLiftCommands: Int,
		 shuttlePhysics: CarriageTravel,
		 maxShuttleCommands: Int,
		 aisleDepth: Int,
		 align: Int,
		 inductChannel: (Int, Channel.Ops[MaterialLoad, InductSourceSignal, LoadAwareXSwitch.XSwitchSignal]),
		 dischargeChannel: (Int, Channel.Ops[MaterialLoad, LoadAwareXSwitch.XSwitchSignal, DischargeSinkSignal]),
		 shuttles: Seq[Int],
		 initialInventory: Map[Int, Map[SlotLocator, MaterialLoad]] = Map.empty)(implicit clock: Clock.Ref, simController: SimulationController.Ref, actorCreator: Processor.ProcessorCreator): (Processor.Ref, Seq[(Int, Processor.Ref)]) = {
			val liftShuttles =
				shuttles.map { idx =>
					val inboundChannel = Channel.Ops(new LiftShuttleChannel(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_in"))
					val outboundChannel = Channel.Ops(new ShuttleLiftChannel(() => Some(5), () => Some(3), Set("c1", "c2"), 1, s"shuttle_${name}_${idx}_out"))
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
