
organization := "org.polynote"
name := "uzhttp"
version := "0.2.5"
scalaVersion := "2.11.12"
crossScalaVersions := Seq("2.11.12", "2.12.12", "2.13.3")

val zioVersion = "1.0.1"

libraryDependencies := Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "org.scalatest" %% "scalatest" % "3.1.0" % "test",

  "dev.zio" %% "zio-test"          % zioVersion % "test",
  "dev.zio" %% "zio-test-sbt"      % zioVersion % "test",

  // http client for testing
  "com.softwaremill.sttp.client" %% "core" % "2.2.5" % "test",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % "2.2.5" % "test"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Ywarn-value-discard",
  "-Xfatal-warnings"
)

// publishing settings
publishMavenStyle := true
homepage := Some(url("https://polynote.org"))
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/polynote/uzhttp"),
    "scm:git@github.com:polynote/uzhttp.git"
  )
)
publishTo := sonatypePublishToBundle.value
developers := List(
  Developer(id = "jeremyrsmith", name = "Jeremy Smith", email = "", url = url("https://github.com/jeremyrsmith")),
  Developer(id = "jonathanindig", name = "Jonathan Indig", email = "", url = url("https://github.com/jonathanindig"))
)
