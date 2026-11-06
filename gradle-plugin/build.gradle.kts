plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.cloudsmith"
version = "0.2.0" // x-release-please-version

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The Maven extension compiles the same shared code into its own jar.
sourceSets {
    main { java.srcDir("../common/src/main/java") }
    test { java.srcDir("../common/src/testFixtures/java") }
}

// Java 17 is the oldest Java that Gradle 9 runs on, and the Maven extension targets it too.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

gradlePlugin {
    website = "https://github.com/cloudsmith-labs/cloudsmith-maven-tools"
    vcsUrl = "https://github.com/cloudsmith-labs/cloudsmith-maven-tools"
    plugins {
        create("cloudsmith") {
            id = "io.cloudsmith.gradle-plugin"
            implementationClass = "io.cloudsmith.gradle.CloudsmithPlugin"
            displayName = "Cloudsmith credentials from the cloudsmith CLI"
            description = "Authenticates Gradle to Cloudsmith Maven repositories with the credential helper of the cloudsmith CLI"
            tags = listOf("cloudsmith", "credentials", "repository", "authentication")
        }
    }
}

// Set by `mise run publish:cloudsmith`.
providers.gradleProperty("cloudsmith.publish.url").orNull?.let { publishUrl ->
    publishing {
        repositories {
            maven {
                name = "cloudsmith"
                url = uri(publishUrl)
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar)
    // The Gradle and the Java under test: by default, the Gradle and the Java that run this build.
    val gradleUnderTest = providers.gradleProperty("it.gradle.home")
    val javaUnderTest = providers.gradleProperty("it.java.home")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOfNotNull(
                "-Dit.plugin.jar=${pluginJar.get().asFile}",
                gradleUnderTest.orNull?.let { "-Dit.gradle.home=$it" },
                javaUnderTest.orNull?.let { "-Dit.java.home=$it" },
            )
        },
    )
}
