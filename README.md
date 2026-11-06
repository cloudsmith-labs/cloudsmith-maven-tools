# Cloudsmith Maven tools

Authenticate Maven, Gradle, and sbt to [Cloudsmith](https://cloudsmith.io)
with the credential helper of the Cloudsmith CLI. Builds need no credentials in
`settings.xml`, `pom.xml`, `gradle.properties`, `build.sbt`, or environment
variables.

The same setup works with a SAML session on a laptop, OIDC in CI, and an API
key.

## How it works

The Maven extension, the Gradle plugin, and the sbt plugin handle each HTTPS
repository in the build:

1. Run `cloudsmith domains list` to learn which hosts serve Cloudsmith Maven
   repositories, including the custom domains of your workspace.
2. For a repository on one of those hosts, run
   `cloudsmith credential-helper generic` and send the credential as Basic
   auth.
3. Leave every other repository unchanged.

If the CLI is missing, not authenticated, or fails, the build runs as if the
extension or plugin were not installed, and logs a warning with the error of
the CLI. The CLI's answers are kept in memory for five minutes, so a build runs
the CLI only a few times.

## Prerequisites

- Java 17 or later.
- The [Cloudsmith CLI](https://docs.cloudsmith.com/developer-tools/cli) 1.22.0
  or later on `PATH`, authenticated with `cloudsmith auth login`, OIDC, or
  `CLOUDSMITH_API_KEY`. Run `cloudsmith whoami` to confirm that the CLI is
  connected.
- For custom domains, a workspace that the CLI knows. Set
  `CLOUDSMITH_WORKSPACE`, or `workspace` in the CLI's `config.ini`.

## Where to get them

Releases are published to the public Cloudsmith repository
[`cloudsmith/maven-tools`](https://cloudsmith.io/~cloudsmith/repos/maven-tools/packages/),
at `https://dl.cloudsmith.io/public/cloudsmith/maven-tools/maven/`. Downloads
from it need no credentials. The steps below install from it. To build from
source instead, see [CONTRIBUTING.md](./CONTRIBUTING.md).

## Use with Maven

Maven 3.9.0 or later is required.

1. Install the extension into your local Maven repository. Maven loads
   `.mvn/extensions.xml` only from Maven Central or your local repository, so
   run this once on each machine, including CI, outside the project:

   <!-- x-release-please-start-version -->
   ```sh
   version=0.2.1
   base=https://dl.cloudsmith.io/public/cloudsmith/maven-tools/maven/io/cloudsmith/cloudsmith-maven-extension/$version
   curl -fsSLO "$base/cloudsmith-maven-extension-$version.jar"
   curl -fsSLO "$base/cloudsmith-maven-extension-$version.pom"
   mvn install:install-file -Dfile=cloudsmith-maven-extension-$version.jar \
       -DpomFile=cloudsmith-maven-extension-$version.pom
   ```
   <!-- x-release-please-end -->

2. Add the extension to `.mvn/extensions.xml` in the root of your project:

   <!-- x-release-please-start-version -->
   ```xml
   <extensions>
     <extension>
       <groupId>io.cloudsmith</groupId>
       <artifactId>cloudsmith-maven-extension</artifactId>
       <version>0.2.1</version>
     </extension>
   </extensions>
   ```
   <!-- x-release-please-end -->

3. Add your Cloudsmith repository to `pom.xml`. Use `<repositories>` to
   download and `<distributionManagement>` to deploy:

   ```xml
   <repositories>
     <repository>
       <id>cloudsmith</id>
       <url>https://dl.cloudsmith.io/basic/WORKSPACE/REPOSITORY/maven/</url>
     </repository>
   </repositories>
   <distributionManagement>
     <repository>
       <id>cloudsmith</id>
       <url>https://maven.cloudsmith.io/WORKSPACE/REPOSITORY/</url>
     </repository>
   </distributionManagement>
   ```

No `<server>` entry is needed in `settings.xml`, and the repository `id` can
be any value. A mirror in `settings.xml` works the same way.

Maven loads `.mvn/extensions.xml` before it reads any POM, so the extension
also covers a parent POM or an import BOM in a private repository.

**Fetch every dependency and plugin through Cloudsmith:** add Maven Central as
an upstream of your Cloudsmith repository. Then give the download URL the id
`central` in both `<repositories>` and `<pluginRepositories>`. The id
`central` replaces Maven Central.

**Credentials in `settings.xml` take precedence.** The extension does not
replace a credential that Maven already has, so a `<server>` entry overrides
the CLI for one repository. Maven 3.10 sends a `<server>` credential only to
the hosts of the repositories and mirrors with the same id. For any other
Cloudsmith host, the extension supplies the CLI credential.

## Use with Gradle

Gradle 7.6 or later is required.

1. Apply the plugin in `settings.gradle.kts`, from the `maven-tools`
   repository:

   <!-- x-release-please-start-version -->
   ```kotlin
   pluginManagement {
       repositories {
           maven { url = uri("https://dl.cloudsmith.io/public/cloudsmith/maven-tools/maven/") }
           gradlePluginPortal()
       }
   }

   plugins {
       id("io.cloudsmith.gradle-plugin") version "0.2.1"
   }
   ```
   <!-- x-release-please-end -->

2. Add your Cloudsmith repository. Use `dependencyResolutionManagement` to
   download and `publishing` to publish:

   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories {
           maven { url = uri("https://dl.cloudsmith.io/basic/WORKSPACE/REPOSITORY/maven/") }
       }
   }
   ```

   ```kotlin
   // build.gradle.kts
   publishing {
       repositories {
           maven {
               name = "cloudsmith"
               url = uri("https://maven.cloudsmith.io/WORKSPACE/REPOSITORY/")
           }
       }
   }
   ```

No credentials are needed in the build or in `gradle.properties`.

Applied in settings, the plugin covers the repositories in `pluginManagement`
and `dependencyResolutionManagement`, and the repositories of every project,
including `maven-publish` repositories. To cover one project only, apply the
plugin in the `plugins` block of that project instead.

**Settings plugins from a private repository:** Gradle resolves all the
plugins in the `plugins` block of `settings.gradle.kts` before it applies one
of them. The plugin cannot cover the other plugins in that block, so they get
a 401 response. Apply the plugin from an init script instead. An init script
in `~/.gradle/init.d` applies the plugin to every build on the machine, before
Gradle reads the settings:

<!-- x-release-please-start-version -->
```kotlin
// ~/.gradle/init.d/cloudsmith.init.gradle.kts
initscript {
    repositories {
        maven { url = uri("https://dl.cloudsmith.io/public/cloudsmith/maven-tools/maven/") }
    }
    dependencies {
        classpath("io.cloudsmith:cloudsmith-gradle-plugin:0.2.1")
    }
}

beforeSettings {
    pluginManager.apply(io.cloudsmith.gradle.CloudsmithPlugin::class.java)
}
```
<!-- x-release-please-end -->

`cloudsmith credential-helper install gradle` writes this file, with Cloudsmith
CLI 1.31.0 or later.

**Fetch every dependency through Cloudsmith:** add Maven Central as an upstream
of your Cloudsmith repository, and make it the only repository.

**Credentials in the build take precedence.** The plugin does not replace
credentials that a repository already has, including credentials of another
type such as `HttpHeaderCredentials`.

The plugin works with the configuration cache. Before Gradle reuses a cached
configuration, it asks the CLI again, so a rotated token makes Gradle configure
the build again.

## Use with sbt

sbt 1.10 or later, or sbt 2.0 or later, is required.

1. Add the plugin to `project/plugins.sbt`, from the `maven-tools`
   repository:

   <!-- x-release-please-start-version -->
   ```scala
   resolvers += "cloudsmith-maven-tools" at "https://dl.cloudsmith.io/public/cloudsmith/maven-tools/maven/"
   addSbtPlugin("io.cloudsmith" % "sbt-cloudsmith" % "0.2.1")
   ```
   <!-- x-release-please-end -->

2. Add your Cloudsmith repository to `build.sbt`. Use `resolvers` to download
   and `publishTo` to publish:

   ```scala
   resolvers += "cloudsmith" at "https://dl.cloudsmith.io/basic/WORKSPACE/REPOSITORY/maven/"
   publishTo := Some("cloudsmith" at "https://maven.cloudsmith.io/WORKSPACE/REPOSITORY/")
   ```

No credentials are needed in `build.sbt` or in `~/.sbt`. The plugin turns on
for every project automatically.

**Fetch every dependency through Cloudsmith:** add Maven Central as an upstream
of your Cloudsmith repository, and set `fullResolvers` to your Cloudsmith
repository only.

**Credentials in the build take precedence.** The plugin adds nothing for a
host that already has credentials in `credentials`.

## Repository URLs

A custom domain belongs to one workspace, so its URLs leave out the workspace.

| Use | `cloudsmith.io` host | Custom domain |
|-----|----------------------|---------------|
| Download | `https://dl.cloudsmith.io/basic/WORKSPACE/REPOSITORY/maven/` | `https://HOST/basic/REPOSITORY/maven/` |
| Deploy or publish | `https://maven.cloudsmith.io/WORKSPACE/REPOSITORY/` | `https://HOST/REPOSITORY/` |

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CLOUDSMITH_CLI_PATH` | Not set | Path to the `cloudsmith` executable. When set, `PATH` is not searched. |
| `CLOUDSMITH_MAVEN_TOOLS_TIMEOUT` | `30` | Time limit, in seconds, for each CLI command. |
| `CLOUDSMITH_MAVEN_TOOLS_TTL` | `300` | How long, in seconds, an answer from the CLI is reused. Failed answers are reused too, so a slow or broken CLI does not stall each download. |

## Troubleshooting

Run Maven with `-X`, Gradle with `--info`, or `last update` in sbt. For each
repository that gets the CLI credential, the log shows
`Using the cloudsmith CLI credential for <repository URL>`.

If the CLI is missing or fails, the build log shows a warning with the error
of the CLI. Behind a TLS-intercepting proxy, set `REQUESTS_CA_BUNDLE` to a CA
bundle that contains the proxy CA.

If that line is missing, check the CLI:

```sh
cloudsmith domains list                                      # Is the host listed?
cloudsmith credential-helper generic > /dev/null && echo ok  # Is there a credential?
```

Known limitations:

- `-Dmaven.resolver.transport=native` or `=wagon` makes Maven skip the
  extension, so the build gets no CLI credential.
- Windows is not tested in CI.

## Security

- The credential is sent only over HTTPS, and only to hosts that
  `cloudsmith domains list` reports as download or Maven hosts.
- The CLI runs without a shell. Relative `PATH` entries are skipped, because
  they resolve against the build directory, which is often an untrusted
  checkout. On Windows, only `cloudsmith.exe` is accepted.
- The token is never written to disk or logged by the extension or plugins.
  Gradle keeps repository credentials in its configuration cache.

To report a vulnerability, see [SECURITY.md](./SECURITY.md).

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) to build and test the project, and
[RELEASING.md](./RELEASING.md) for the release process.

## License

Apache-2.0. See [LICENSE](./LICENSE).
