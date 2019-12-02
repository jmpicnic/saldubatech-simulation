
scalaVersion := "2.12.10"

resolvers += Resolver.url("bintray-sbt-plugins",
	url("https://dl.bintray.com/sbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

/**
	* doc = "https://sbt-native-packager.readthedocs.io/en/stable/index.html"
	* loc = "https://index.scala-lang.org/sbt/sbt-native-packager/sbt-native-packager/1.3.9?target=_2.12_1.0"
	*/
addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.3.19")
