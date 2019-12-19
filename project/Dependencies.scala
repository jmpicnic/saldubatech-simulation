/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */
import DependenciesSpecification._

object Dependencies {
	lazy val modules: Seq[Package] = Seq(
		ApacheCommons,
		Akka,
		Core,
		DB,
		//Graph,
		Logging,
		//Misc,
		Slick
	)
	lazy val production: Seq[sbt.ModuleID] = modules.map(_.deps).fold(Seq.empty)((acc, el) => acc ++ el)
	lazy val test: Seq[sbt.ModuleID] = modules.map(_.testDeps.map(_ % "test")).fold(Seq.empty)((acc, el) => acc ++ el)

	lazy val all: Seq[sbt.ModuleID] = production ++ test
}
