plugins {
    `java-library`
    `maven-publish`
}

group = "io.cloudsmith.examples"
version = providers.environmentVariable("EXAMPLE_VERSION").get()

val workspace = providers.environmentVariable("CLOUDSMITH_ORG").get()
val repository = providers.environmentVariable("CLOUDSMITH_REPO").get()

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "example-gradle"
            from(components["java"])
        }
    }
    // No credentials here or in gradle.properties: the plugin supplies them.
    repositories {
        maven {
            name = "cloudsmith"
            // For a custom domain, use https://<host>/<repository>/
            url = uri(
                providers.gradleProperty("cloudsmith.deploy.url")
                    .getOrElse("https://maven.cloudsmith.io/$workspace/$repository/"),
            )
        }
    }
}
