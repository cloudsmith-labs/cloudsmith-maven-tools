pluginManagement {
    // Builds the plugin from this checkout. Outside this repository, request a released version.
    includeBuild("../gradle-plugin")
}

plugins {
    id("io.cloudsmith.gradle-plugin")
}

val workspace = providers.environmentVariable("CLOUDSMITH_ORG").get()
val repository = providers.environmentVariable("CLOUDSMITH_REPO").get()

// No credentials here or in gradle.properties: the plugin supplies them.
dependencyResolutionManagement {
    repositories {
        maven {
            name = "cloudsmith"
            // For a custom domain, use https://<host>/basic/<repository>/maven/
            url = uri(
                providers.gradleProperty("cloudsmith.resolve.url")
                    .getOrElse("https://dl.cloudsmith.io/basic/$workspace/$repository/maven/"),
            )
        }
    }
}

rootProject.name = "examples"
include("library", "app")
