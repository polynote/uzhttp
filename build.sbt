organization := "org.polynote"
name := "uzhttp"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.11.12"
crossScalaVersions := Seq("2.11.12", "2.12.10", "2.13.1")

val zioVersion = "1.0.0-RC18-2"

libraryDependencies := Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "org.scalatest" %% "scalatest" % "3.1.0" % "test",

  "dev.zio" %% "zio-test"          % zioVersion % "test",
  "dev.zio" %% "zio-test-sbt"      % zioVersion % "test",

  // http client for testing
  "com.softwaremill.sttp.client" %% "core" % "2.0.3" % "test",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % "2.0.3" % "test"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Ywarn-value-discard",
  "-Xfatal-warnings"
)