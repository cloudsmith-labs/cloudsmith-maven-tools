val workspace = sys.env("CLOUDSMITH_ORG")
val repository = sys.env("CLOUDSMITH_REPO")
val exampleVersion = sys.env.getOrElse("EXAMPLE_VERSION", "0.1.0")

ThisBuild / organization := "io.cloudsmith.examples"
ThisBuild / version := exampleVersion
ThisBuild / autoScalaLibrary := false
ThisBuild / crossPaths := false

// No credentials here or in ~/.sbt: the plugin supplies them.
lazy val library = project.settings(
  name := "example-sbt",
  publishMavenStyle := true,
  // For a custom domain, use https://<host>/<repository>/
  publishTo := Some(
    "cloudsmith" at sys.props.getOrElse("cloudsmith.deploy.url", s"https://maven.cloudsmith.io/$workspace/$repository/")
  )
)

// Cloudsmith is the only resolver, so every dependency, and the Scala tools that sbt needs, come
// from Cloudsmith, which proxies Maven Central as an upstream.
lazy val app = project.settings(
  // For a custom domain, use https://<host>/basic/<repository>/maven/
  fullResolvers := Seq(
    "cloudsmith" at sys.props.getOrElse(
      "cloudsmith.resolve.url",
      s"https://dl.cloudsmith.io/basic/$workspace/$repository/maven/"
    )
  ),
  libraryDependencies += "io.cloudsmith.examples" % "example-sbt" % exampleVersion
)
