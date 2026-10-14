// Applies the plugin from CLOUDSMITH_PLUGIN_JAR to this build, so the plugin authenticates the
// publish of its own release. Used by `mise run publish:cloudsmith`.
initscript {
    dependencies {
        classpath(files(System.getenv("CLOUDSMITH_PLUGIN_JAR")))
    }
}

beforeSettings {
    pluginManager.apply(io.cloudsmith.gradle.CloudsmithPlugin::class.java)
}
