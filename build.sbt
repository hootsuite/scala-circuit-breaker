import Settings._
import Version._

name         := "scala-circuit-breaker"
organization := "com.hootsuite"
organizationName := "Hootsuite Media Inc."
organizationHomepage := Some(url("http://hootsuite.com"))
version      := Version.project
scalaVersion := Version.scala

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

parallelExecution in Test := false

resolvers += Resolver.jcenterRepo
libraryDependencies ++= Seq(
  "org.slf4j"         %  "slf4j-api"        % "1.7.13",
  "ch.qos.logback"    %  "logback-core"     % "1.1.3",
  "ch.qos.logback"    %  "logback-classic"  % "1.1.3",
  "org.scalatest"     %% "scalatest"        % "2.2.6"   % Test
)

Settings.publishSettings
