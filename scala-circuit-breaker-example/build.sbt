lazy val root = project.in(file(".")).enablePlugins(PlayScala)

name         := "scala-circuit-breaker-example"
organization := "com.hootsuite"
version      := "1.0.0"
scalaVersion := "2.11.7"
organizationName := "Hootsuite Media Inc."
organizationHomepage := Some(url("http://hootsuite.com"))

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers += Resolver.jcenterRepo // Adds Bintray to resolvers

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws"                  % "2.4.3",
  "com.hootsuite"     %% "scala-circuit-breaker"    % "1.0.0"
)

addCommandAlias("runServer", "~run")
addCommandAlias("runClient", "runMain com.hootsuite.circuitbreakerexample.client.CircuitBreakerClient")
