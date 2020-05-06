/*resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases/"
)*/
import DependenciesSpecification._

ThisBuild / fork := true
ThisBuild / run / javaOptions += "-Xmx4G -Xms1024M "
ThisBuild / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.ScalaLibrary
ThisBuild / organization := "com.saldubatech"
ThisBuild / organizationName := "Salduba Technologies"
ThisBuild / scalaVersion := configuredScalaVersion
ThisBuild / version      := "0.1.0-SNAPSHOT"
// https://mvnrepository.com/artifact/org.scalatest/scalatest
val scalatest = "org.scalatest" %% "scalatest" % "3.1.1" % Test
ThisBuild / libraryDependencies += scalatest
ThisBuild / libraryDependencies ++= Dependencies.all
ThisBuild / scalaSource in Test := baseDirectory.value / "src/test/scala"

lazy val foundation:Project = (project in file("foundation"))
  .settings(
    name := "foundation"
  )

lazy val protocols: Project = (project in file("protocols"))
  .settings(
    name := "protocols"
  ).dependsOn(foundation)

lazy val equipment:Project = (project in file("equipment"))
  .settings(
    name := "dcf-equipment",
  )
  .dependsOn(protocols).dependsOn(foundation % "test -> test;compile->compile")

lazy val network: Project = (project in file("network"))
  .settings(
    name := "dcf-network",
  )
  .dependsOn(foundation % "test -> test;compile->compile")
  .dependsOn(protocols)
  .dependsOn(equipment)

lazy val root = (project in file("."))
  .settings(
    name := "dcf-poc",
  ).aggregate(foundation, protocols, equipment, network)

//name := "simAkka"
//version := "0.1"
//scalaVersion := "2.12.7"

//libraryDependencies ++= _Dependencies.all

  // Postgress DB & ORM

