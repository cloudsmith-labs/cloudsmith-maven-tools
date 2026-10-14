enablePlugins(SbtPlugin)

organization := "io.cloudsmith"
name := "sbt-cloudsmith"
version := "0.1.0" // x-release-please-version
description := "Authenticates sbt to Cloudsmith Maven repositories with the credential helper of the cloudsmith CLI"
homepage := Some(url("https://github.com/cloudsmith-labs/cloudsmith-maven-tools"))
licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/cloudsmith-labs/cloudsmith-maven-tools"),
    "scm:git:https://github.com/cloudsmith-labs/cloudsmith-maven-tools.git"
  )
)
developers := List(Developer("cloudsmith", "Cloudsmith", "support@cloudsmith.io", url("https://cloudsmith.com")))

// `+publishSigned` stages a release for `sonaRelease`, which uploads it to Maven Central. See RELEASING.md.
publishTo := localStaging.value

// sbt 1 plugins build with Scala 2.12, and sbt 2 plugins with the Scala 3 of sbt 2.
scalaVersion := "2.12.21"
crossScalaVersions := Seq("2.12.21", "3.8.4")
pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" => "1.10.11" // The oldest sbt 1 that CI tests
    case _ => "2.0.0"
  }
}

// The Maven extension and the Gradle plugin compile the same shared code into their own jars.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "common" / "src" / "main" / "java"
Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "common" / "src" / "testFixtures" / "java"

// javac compiles the Java first, because the Scala 2.12 compiler cannot read Java records. For
// the same reason Scaladoc cannot run, so the javadoc jar that Maven Central needs is empty.
compileOrder := CompileOrder.JavaThenScala
Compile / packageDoc / mappings := Seq.empty

javacOptions ++= Seq("--release", "17", "-Xlint:all")
scalacOptions ++= Seq("-release", "17", "-deprecation", "-feature", if (scalaBinaryVersion.value == "2.12") "-Xfatal-warnings" else "-Werror")

libraryDependencies ++= Seq(
  "org.junit.jupiter" % "junit-jupiter" % "5.14.4" % Test,
  "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test
)

// The integration tests run real builds with the packaged plugin. Set -Dit.sbt.version to choose
// the sbt under test, and -Dit.java.home to choose its Java.
Test / fork := true
Test / javaOptions ++= {
  val defaultSbt = if (scalaBinaryVersion.value == "2.12") sbtVersion.value else "2.0.10"
  Seq(
    s"-Dit.plugin.jar=${(Compile / packageBin).value}",
    s"-Dit.sbt.version=${sys.props.getOrElse("it.sbt.version", defaultSbt)}"
  ) ++ sys.props.get("it.java.home").map(home => s"-Dit.java.home=$home")
}
