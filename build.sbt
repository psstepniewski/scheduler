name := "scheduler"
maintainer := "pawel@stepniewski.tech"
Universal / packageName := "scheduler"

version := "1.0"

lazy val `scheduler` = (project in file(".")).enablePlugins(PlayScala)

resolvers += "Akka Snapshot Repository" at "https://repo.akka.io/snapshots/"

scalaVersion := "2.13.5"

val akkaVersion = "2.6.19"
val akkaProjectionVersion = "1.2.4"
val slickVersion = "3.3.3"

libraryDependencies ++= Seq( jdbc , ehcache , ws , specs2 % Test , guice )

libraryDependencies ++= Seq(
  "org.postgresql" % "postgresql" % "42.4.0",

  "org.flywaydb" %% "flyway-play" % "7.20.0",

  "com.typesafe.slick" %% "slick" % slickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,

  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka"  %% "akka-cluster-sharding-typed" % akkaVersion,

  "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.0.1",

  "org.playframework.anorm" %% "anorm" % "2.6.10",

  "org.quartz-scheduler" % "quartz" % "2.3.2",

  "com.softwaremill.common" %% "id-generator" % "1.3.1",

  "org.apache.kafka" % "kafka-clients" % "3.2.0",

  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
)
