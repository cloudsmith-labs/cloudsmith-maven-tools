plugins {
    java
}

dependencies {
    implementation(
        "io.cloudsmith.examples:example-gradle:" +
            providers.environmentVariable("EXAMPLE_VERSION").getOrElse("latest.release"),
    )
}
