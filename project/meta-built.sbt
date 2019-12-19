scalaVersion := "2.12.10"


resolvers += Resolver.url("bintray-sbt-plugins",
	url("https://dl.bintray.com/sbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

/**
	* doc = "https://sbt-native-packager.readthedocs.io/en/stable/index.html"
	* loc = "https://index.scala-lang.org/sbt/sbt-native-packager/sbt-native-packager/1.3.9?target=_2.12_1.0"
	*/
addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.3.21")
addSbtPlugin("io.spray" %% "sbt-revolver" % "0.9.1")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.4"
//updateOptions := updateOptions.value.withLatestSnapshots(true)

addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.27")