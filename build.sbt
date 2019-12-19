/*resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases/"
)*/

ThisBuild / organization := "com.saldubatech"
ThisBuild / scalaVersion := "2.12.10"
ThisBuild / version      := "0.1.0-SNAPSHOT"


lazy val foundation:Project = (project in file("foundation"))
  .settings(
    name := "foundation",
    libraryDependencies ++= _Dependencies.all
  )

lazy val v1:Project = (project in file("v1"))
  .settings(
    name := "v1",
    libraryDependencies := _Dependencies.all
  ).dependsOn(foundation)

lazy val equipment:Project = (project in file("equipment"))
  .settings(
    name := "dcf-equipment",
    libraryDependencies := _Dependencies.all
  )
  .dependsOn(foundation, v1)

lazy val network: Project = (project in file("network"))
  .settings(
    name := "dcf-network",
    libraryDependencies := _Dependencies.all
  )
  .dependsOn(foundation, v1)
  .dependsOn(equipment)

lazy val root = (project in file("."))
  .settings(
    name := "dcf-poc",
    libraryDependencies := _Dependencies.all
  ).aggregate(foundation, v1, equipment, network)

//name := "simAkka"
//version := "0.1"
//scalaVersion := "2.12.7"

//libraryDependencies ++= _Dependencies.all

  // Postgress DB & ORM

