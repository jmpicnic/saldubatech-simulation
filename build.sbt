/*resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases/"
)*/
import DependenciesSpecification._


ThisBuild / organization := "com.saldubatech"
ThisBuild / organizationName := "Salduba Technologies"
ThisBuild / scalaVersion := configuredScalaVersion
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / libraryDependencies ++= Dependencies.all

lazy val foundation:Project = (project in file("foundation"))
  .settings(
    name := "foundation"
  )

lazy val v1:Project = (project in file("v1"))
  .settings(
    name := "v1",
  ).dependsOn(foundation % "test -> test;compile->compile")

lazy val equipment:Project = (project in file("equipment"))
  .settings(
    name := "dcf-equipment",
  )
  .dependsOn(foundation % "test -> test;compile->compile", v1)

lazy val network: Project = (project in file("network"))
  .settings(
    name := "dcf-network",
  )
  .dependsOn(foundation % "test -> test;compile->compile", v1)
  .dependsOn(equipment)

lazy val root = (project in file("."))
  .settings(
    name := "dcf-poc",
  ).aggregate(foundation, v1, equipment, network)

//name := "simAkka"
//version := "0.1"
//scalaVersion := "2.12.7"

//libraryDependencies ++= _Dependencies.all

  // Postgress DB & ORM

