/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

import sbt._

object DependenciesSpecification {
  val configuredScalaVersion = "2.12.10"

  trait Package {
    val deps: Seq[ModuleID]
    val testDeps: Seq[ModuleID]
    val additionalResolver: Option[MavenRepository] = None
  }

  def compileLibraries(modules: Seq[Package]) = {
    val main = modules.map(_.deps).fold(Seq.empty)((acc, el) => acc ++ el)
    val test = modules.map(_.testDeps.map(_ % Test)).fold(Seq.empty)((acc, el) => acc ++ el)
    main ++ test
  }


  object Vals {
    val EMPTY = Seq.empty[ModuleID]
  }

  object Squants extends Package {
    lazy val deps = Seq {
      "org.typelevel" %% "squants" % "1.4.0"
    }
    lazy val testDeps = Vals.EMPTY
  }

  object Geodesy extends Package {
    lazy val deps = Seq {
      "org.gavaghan" % "geodesy" % "1.1.3"
    }
    lazy val testDeps = Vals.EMPTY
  }

  object Eval extends Package {
    lazy val deps = Seq {
      "org.scala-lang" % "scala-compiler" % configuredScalaVersion
    }
    lazy val testDeps = Vals.EMPTY
  }

  object Swagger extends Package {
    lazy val deps = Seq(
      "io.swagger" % "swagger-jaxrs" % "1.5.14",
      "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.9.2"
    )
    lazy val testDeps = Vals.EMPTY
  }

  object AkkaHttp extends Package  {
    val version = "10.1.11"
    val deps = Seq(
      "com.typesafe.akka" %% "akka-http",
      "com.typesafe.akka" %% "akka-http-spray-json").map(_ % version) ++
      Seq(
        "ch.megard" %% "akka-http-cors" % "0.4.1",
        //"com.typesafe.akka" %% "akka-http-spray-json" % "10.0.9",
        "de.heikoseeberger" %% "akka-http-circe" % "1.30.0"
      )
    lazy val testDeps = Seq(
      "com.typesafe.akka" %% "akka-http-testkit" % version
    )
  }

  object Akka extends Package {
    lazy val version = "2.6.1"
    lazy val deps = Seq(
      "com.typesafe.akka" %% "akka-actor",
      "com.typesafe.akka" %% "akka-slf4j",
      "com.typesafe.akka" %% "akka-stream",
      "com.typesafe.akka" %% "akka-actor-typed",
      "com.typesafe.akka" %% "akka-stream-typed"
    ).map(_ % version)
    lazy val testDeps = Seq(
      "com.typesafe.akka" %% "akka-testkit",
      "com.typesafe.akka" %% "akka-stream-testkit",
      "com.typesafe.akka" %% "akka-actor-testkit-typed"
    ).map(_ % version)
  }

  object Circe extends Package {
    val version = "0.12.2"
    val deps: Seq[ModuleID] = Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
      "io.circe" %% "circe-literal"
    ).map(_ % version) ++ Seq("io.circe" %% "circe-java8" % "0.12.0-M1") ++
      Seq("io.circe" %% "circe-optics" % "0.12.0")
    lazy val testDeps = Vals.EMPTY
  }

  object Spray extends Package {
    // https://mvnrepository.com/artifact/io.spray/spray-json
    val deps = Seq("io.spray" %% "spray-json" % "1.3.5")
    lazy val testDeps = Vals.EMPTY
  }

  object Cats extends Package {
    val deps = Seq (
      //https://github.com/typelevel/cats
      "org.typelevel" %% "cats-core" % "2.0.0",
      "org.typelevel" %% "kittens" % "2.0.0"
    )
    lazy val testDeps = Seq (
      "org.typelevel" %% "cats-testkit" % "2.0.0",
      "org.typelevel" %% "cats-laws" % "2.0.0"
    )
  }

  object Sangria extends Package  {
    lazy val version = "1.4.2"
    lazy val deps = Seq(
      "org.sangria-graphql" %% "sangria" % version,//"1.3.0",
      "org.sangria-graphql" %% "sangria-circe" % "1.2.1"
      //    "org.sangria-graphql" %% "sangria-spray-json" % "1.0.1"
    )
    lazy val testDeps = Vals.EMPTY
  }

  object JaxBBackPort extends Package {
    val version = "2.2.11"

    lazy val deps = Seq("javax.xml.bind" % "jaxb-api",
      "com.sun.xml.bind" % "jaxb-core",
      "com.sun.xml.bind" % "jaxb-impl").map(_ % version) ++
      Seq("javax.activation" % "activation" % "1.1.1")
    lazy val testDeps = Vals.EMPTY
  }

  object Slick extends Package {
    lazy val version = "3.3.2"
    val deps = Seq(
      "com.typesafe.slick" %% "slick-hikaricp",
      "com.typesafe.slick" %% "slick"
    ).map(_ % version)
    lazy val testDeps = Seq {
      "com.typesafe.slick" %% "slick-testkit"
    }.map(_ % version)
  }

  // TO ADD: https://github.com/locationtech/jts
  object SlickPG extends Package {
    //     https://github.com/tminglei/slick-pg
    lazy val version = "0.18.1"
    val deps = Seq(
      "com.github.tminglei" %% "slick-pg" % version,
      "com.github.tminglei" %% "slick-pg_circe-json" % version
    )
    val testDeps = Seq.empty[ModuleID]
  }

  object SlickRepo extends Package {
    val deps = Seq(
      "com.byteslounge" %% "slick-repo" % "1.5.3" // Slick Repo
    )
    lazy val testDeps = Vals.EMPTY
  }

  // https://mvnrepository.com/artifact/io.underscore/slickless
  object Slickless extends Package {
    val deps =
      Seq("io.underscore" %% "slickless" % "0.3.6")

    lazy val testDeps = Vals.EMPTY
  }


  object DB extends Package {
    lazy val testDeps = Seq(
      // https://mvnrepository.com/artifact/com.dimafeng/testcontainers-scala
      "com.dimafeng" %% "testcontainers-scala" % "0.33.0",
      // https://mvnrepository.com/artifact/org.testcontainers/postgresql
      "org.testcontainers" % "postgresql" % "1.12.2"
    )

    lazy val deps = Seq(
      "org.postgresql" % "postgresql" % "42.2.5",// Upgraded from "9.4-1206-jdbc42", // Postgres access
      "com.zaxxer" % "HikariCP" % "3.4.1", // Connection Pool
      "org.flywaydb" % "flyway-core" % "6.0.6" // DB Migration
    ) ++ testDeps // Needed to be able to export the test utilities.
  }

  object ApacheCommons extends Package {
    val deps: Seq[ModuleID] = Seq(
      // https://mvnrepository.com/artifact/org.apache.commons/commons-math3
      "org.apache.commons" % "commons-math3" % "3.6.1",
      // https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
      "org.apache.commons" % "commons-lang3" % "3.9"
    )
    val testDeps: Seq[ModuleID] = Seq.empty
  }

  object Scalaz extends Package {
    lazy val scalazVersion = "7.2.30"
    lazy val shapelessVersion = "2.3.3"
    val deps = Seq(
      "org.scalaz" %% "scalaz-core" % scalazVersion,
      "org.scalaz" %% "scalaz-effect" % scalazVersion,
      // https://mvnrepository.com/artifact/com.chuusai/shapeless
      "com.chuusai" %% "shapeless" % shapelessVersion
    )
    lazy val testDeps = Vals.EMPTY
  }

  object GeoMwundo extends Package {
    lazy val version: String = "0.5.0"
    val deps: Seq[ModuleID] = Seq(
      "com.monsanto.labs" %% "mwundo-core" % version,
      //"com.monsanto.labs" %% "mwundo-spray" % version
      "com.monsanto.labs" %% "mwundo-circe" % version
    )
    lazy val testDeps: Seq[ModuleID] = Vals.EMPTY
    override val additionalResolver: Option[sbt.MavenRepository] = Some(Resolver.bintrayRepo("monsanto", "maven"))
  }

  object Log4JCore extends Package {
    val version = "2.13.0"
    lazy val deps = Seq(
      "org.apache.logging.log4j" % "log4j-core",
      // https://mvnrepository.com/artifact/org.apache.logging.log4j/log4j-api
      "org.apache.logging.log4j" % "log4j-api",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" // Log4JSLF4J Bridge
      //"log4j" % "log4j" % "1.2.17" ????
    ).map(_ % version)
    lazy val testDeps = Vals.EMPTY
  }

  object Authentication extends Package {
    lazy val deps = Seq(
      "com.nimbusds" % "nimbus-jose-jwt" % "4.23"
    )
    lazy val testDeps = Vals.EMPTY
  }

  object Logging extends Package {
    lazy val deps = Seq(
      "org.slf4j" % "slf4j-api" % "1.7.29",
      //"org.slf4j" % "slf4j-log4j12" % "1.7.27", Not needed, using the Log4J bundled bridge
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
      //"de.heikoseeberger" %% "akka-log4j" % "1.6.1"
    )
    lazy val testDeps = Seq(
      //"org.slf4j" % "slf4j-log4j12" % "1.7.25",// % Test
    )
  }

  object Core extends Package {
    lazy val deps = Seq("org.scalatest" %% "scalatest" % "3.0.8")// This is needed here in order to provide test utilities as part of the "distribution" Vals.EMPTY
    lazy val testDeps = Seq("org.scalatest" %% "scalatest" % "3.0.8")
  }

  object Graphs extends Package {
    override val deps: Seq[sbt.ModuleID] = Seq(
      "org.scala-graph" %% "graph-core" % "1.13.0"
    )
    override val testDeps: Seq[sbt.ModuleID] = Vals.EMPTY
  }



  object Caliban extends Package {
    lazy val deps = Seq("com.github.ghostdogpr" %% "caliban" % "0.2.0")
    lazy val testDeps = Vals.EMPTY
  }

  object Sttp extends Package {
    override val deps: Seq[sbt.ModuleID] = Seq(
      "com.softwaremill.sttp" %% "core" % "1.7.2"

    )
    override val testDeps = Vals.EMPTY
  }

  object SttpClient extends Package {
    // https://mvnrepository.com/artifact/com.softwaremill.sttp.client/
    val version = "2.0.0-RC5"
    override val deps: Seq[sbt.ModuleID] = Seq(
      "com.softwaremill.sttp.client" %% "core",
      "com.softwaremill.sttp.client" %% "async-http-client-backend",
      "com.softwaremill.sttp.client" %% "async-http-client-backend-future",
      "com.softwaremill.sttp.client" %% "json-common",
      //"com.softwaremill.sttp.client" %% "model",
      "com.softwaremill.sttp.client" %% "circe",
      "com.softwaremill.sttp.client" %% "akka-http-backend",
      //      "com.softwaremill.sttp.client" %% "httpclient-backend",
      "com.softwaremill.sttp.client" %% "async-http-client-backend-future"
    ).map(_ % version)
    override val testDeps = Vals.EMPTY
  }
  object TapirServer extends Package {
    val version = "0.12.8"
    override val deps:Seq[ModuleID] = Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core",
      "com.softwaremill.sttp.tapir" %% "tapir-akka-http-server",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe",
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs",
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe",
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml",
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-akka-http"
    ).map( _ % version)
    override val testDeps: Seq[ModuleID] = Seq.empty
  }

  object TapirClient extends Package {
    val version = "0.12.8"
    override val deps:Seq[ModuleID] = Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client",
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs",
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-akka-http",
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"
    ).map( _ % version)
    override val testDeps: Seq[ModuleID] = Seq.empty
  }

  /*object ScalaPB extends Package {
    override val deps: Seq[ModuleID] = Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
    override val testDeps =  Seq.empty[ModuleID]
  }*/

  /* To add in the future:
https://neotypes.github.io/neotypes/docs.html

 */
}

