/*resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases/"
)*/


name := "simAkka"

version := "0.1"

scalaVersion := "2.12.10"

lazy val akkaVersion = "2.6.0"


libraryDependencies ++= Seq(
  // General Utilities
  "org.apache.commons" % "commons-math3" % "3.5",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",

  // Postgress DB & ORM
  "org.postgresql" % "postgresql" % "9.4-1206-jdbc42", // Postgres access
  "com.zaxxer" % "HikariCP" % "3.3.0", // Connection Pool
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.0",
  "com.typesafe.slick" %% "slick" % "3.3.0", // Slick
  "com.typesafe.slick" %% "slick-testkit" % "3.3.0" % "test",
  "com.typesafe.akka" %% "akka-http"   % "10.1.7",

// Logging
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "org.slf4j" % "slf4j-log4j12" % "1.7.25",// % Test
  //"ch.qos.logback" % "logback-classic" % "1.1.7",
  "log4j" % "log4j" % "1.2.17",
  //"org.slf4j" % "slf4j-simple" % "1.7.25",// % Test,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
  // Actors
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion
)
