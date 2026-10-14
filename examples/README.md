# Examples

These examples publish a small library to a real Cloudsmith repository, and
then compile an app that depends on it. Maven, Gradle, and sbt each build the
same `library/` and `app/` sources:

| Tool | `library/` publishes | `app/` fetches |
|------|----------------------|----------------|
| Maven | `io.cloudsmith.examples:example-maven` | Every dependency and plugin, through Cloudsmith only, from an empty local repository |
| Gradle | `io.cloudsmith.examples:example-gradle` | The library, from Cloudsmith, the only repository |
| sbt | `io.cloudsmith.examples:example-sbt` | Every dependency and the Scala tools, through Cloudsmith only |

No example has credentials in a build file, `settings.xml`,
`gradle.properties`, `~/.sbt`, or an environment variable that a build tool
reads. Maven loads the extension from `.mvn/extensions.xml`. Gradle and sbt
build their plugin from this checkout, through `settings.gradle.kts` and
`project/plugins.sbt`.

## Prerequisites

- [mise](https://mise.jdx.dev/), which installs everything else.
- A Cloudsmith repository that you can write to. For the Maven and sbt apps,
  add Maven Central as an upstream of the repository.
- The Cloudsmith CLI authenticated with `cloudsmith auth login` or
  `CLOUDSMITH_API_KEY`.

## Run the examples

1. Set the repository to use:

   ```sh
   export CLOUDSMITH_ORG=WORKSPACE
   export CLOUDSMITH_REPO=REPOSITORY
   ```

2. For a custom domain, also set its URLs:

   ```sh
   export CLOUDSMITH_DEPLOY_URL="https://HOST/REPOSITORY/"
   export CLOUDSMITH_RESOLVE_URL="https://HOST/basic/REPOSITORY/maven/"
   ```

3. Run the examples from the root of the repository:

   ```sh
   mise run example:maven
   mise run example:gradle
   mise run example:sbt
   ```

Each run publishes a new version, because Cloudsmith does not accept the same
release version twice. Set `EXAMPLE_VERSION` to choose the version.

Cloudsmith processes an upload before it serves it, so a new version can return
404 for several minutes. Each run retries the app build for up to 15 minutes.
