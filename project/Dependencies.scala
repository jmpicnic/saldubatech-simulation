/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

import sbt._

trait PackageDependency {
  val deps: Seq[ModuleID]
  val testDeps: Seq[ModuleID]
}

object AkkaHttp extends PackageDependency  {
  val version = "10.1.7"
  val deps = Seq(
    "com.typesafe.akka" %% "akka-http").map(_ % version)
  lazy val testDeps = Seq.empty
}

object Akka extends PackageDependency  {
  lazy val version = "2.6.0"
  lazy val deps = Seq(
    "com.typesafe.akka" %% "akka-actor",
    "com.typesafe.akka" %% "akka-slf4j",
    "com.typesafe.akka" %% "akka-stream",
  ).map(_ % version)
  lazy val testDeps = Seq(
    "com.typesafe.akka" %% "akka-testkit"
  ).map(_ % version)
}

object Circe extends PackageDependency {
  val version = "0.11.1"
  val deps = Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser",
  ).map(_ % version)
  lazy val testDeps = Seq.empty
}

object Sangria extends PackageDependency  {
  lazy val version = "1.4.2"
  lazy val deps = Seq(
    "org.sangria-graphql" %% "sangria" % version,//"1.3.0",
    "org.sangria-graphql" %% "sangria-circe" % "1.1.0"
  ) ++
  Seq()
  lazy val testDeps = Seq.empty
}

object JaxBBackPort extends PackageDependency  {
  val version = "2.2.11"

  lazy val deps = Seq("javax.xml.bind" % "jaxb-api",
    "com.sun.xml.bind" % "jaxb-core",
    "com.sun.xml.bind" % "jaxb-impl").map(_ % version) ++
    Seq("javax.activation" % "activation" % "1.1.1")
  lazy val testDeps = Seq.empty
}

object Slick extends PackageDependency  {
  lazy val version = "3.3.2"
  val deps = Seq(
    "com.typesafe.slick" %% "slick-hikaricp",
    "com.typesafe.slick" %% "slick"
  )map(_ % version)
  lazy val testDeps = Seq {
    "com.typesafe.slick" %% "slick-testkit"
  }.map(_ % version)
}

object DB extends PackageDependency  {
  lazy val deps = Seq(
    "org.postgresql" % "postgresql" % "9.4-1206-jdbc42", // Postgres access
    "com.zaxxer" % "HikariCP" % "3.3.0", // Connection Pool
  )
  lazy val testDeps = Seq.empty
}

object Graph extends PackageDependency {
  // http://www.scala-graph.org/
  lazy val deps = Seq(
    "org.scala-graph" %% "graph-core" % "1.12.5",
    "org.scala-graph" %% "graph-constrained" %	"1.12.7",
    "org.scala-graph" %% "graph-dot" % "1.12.1",
    "org.scala-graph" %% "graph-json" %	"1.12.1"
  )
  lazy val testDeps = Seq.empty
}
object Misc extends PackageDependency  {
  val deps = Seq(
    "org.apache.commons" % "commons-math3" % "3.5",
    "org.scala-graph" %% "graph-core" % "1.12.5"
  )
  lazy val testDeps = Seq.empty
}

object Logging extends PackageDependency  {
  lazy val deps = Seq(
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "org.slf4j" % "slf4j-log4j12" % "1.7.25",// % Test
  //"ch.qos.logback" % "logback-classic" % "1.1.7",
    "log4j" % "log4j" % "1.2.17",
  //"org.slf4j" % "slf4j-simple" % "1.7.25",// % Test,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
  )
  lazy val testDeps = Seq.empty
}

object Core extends PackageDependency {
  lazy val deps = Seq.empty
  lazy val testDeps = Seq("org.scalatest" %% "scalatest" % "3.0.5")
}

object _Dependencies {
  lazy val modules: Seq[PackageDependency] = Seq(
    Akka,
    Core,
    DB,
    Graph,
    Logging,
    Misc,
    Slick
  )
  lazy val production: Seq[sbt.ModuleID] = modules.map(_.deps).fold(Seq.empty)((acc, el) => acc ++ el)
  lazy val test: Seq[sbt.ModuleID] = modules.map(_.testDeps.map(_ % "test")).fold(Seq.empty)((acc, el) => acc ++ el)

  lazy val all: Seq[sbt.ModuleID] = production ++ test
}