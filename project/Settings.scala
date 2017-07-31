import bintray.BintrayPlugin.autoImport._
import sbt.Keys._
import sbt._
import Version._

object Settings {
  lazy val publishSettings =
    if (Version.project.endsWith("-SNAPSHOT"))
      Seq(
        publishTo := Some("Artifactory Realm".at("http://oss.jfrog.org/artifactory/oss-snapshot-local")),
        bintrayReleaseOnPublish := false,
        // Only setting the credentials file if it exists (#52)
        credentials := List(Path.userHome / ".bintray" / ".artifactory")
          .filter(_.exists)
          .map(Credentials(_)),
        licenses := ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt")) :: Nil // this is required! otherwise Bintray will reject the code
      )
    else
      Seq(
        organization := "com.hootsuite",
        pomExtra := <scm>
          <url>https://github.com/hootsuite/scala-circuit-breaker</url>
          <connection>https://github.com/hootsuite/scala-circuit-breaker</connection>
        </scm>
          <developers>
            <developer>
              <id>diego.alvarez</id>
              <name>Diego Alvarez</name>
              <url>http://www.hootsuite.com/</url>
            </developer>
          </developers>,
        publishArtifact in Test := false,
        homepage := Some(url("https://github.com/hootsuite/scala-circuit-breaker")),
        publishMavenStyle := true,
        pomIncludeRepository := { _ =>
          false
        },
        resolvers += Resolver.url("scala-circuit-breaker", url("http://dl.bintray.com/hootsuite/maven"))(
          Resolver.ivyStylePatterns
        ),
        licenses := ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt")) :: Nil // this is required! otherwise Bintray will reject the code
      )
}
