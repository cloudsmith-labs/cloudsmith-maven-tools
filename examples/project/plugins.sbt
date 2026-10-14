// Builds the plugin from this checkout. Outside this repository, use addSbtPlugin with a released
// version.
lazy val root = project.in(file(".")).dependsOn(RootProject(file("../../sbt-plugin")))
