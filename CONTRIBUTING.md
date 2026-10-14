# Contributing

Thank you for contributing to the Cloudsmith Maven tools.

## Project layout

| Directory | Contents |
|-----------|----------|
| `common/` | Code that runs the Cloudsmith CLI and reads its output, with its tests. It has no build file: each build compiles it into its own jar. |
| `maven-extension/` | The Maven extension. Maven builds it. |
| `gradle-plugin/` | The Gradle plugin. Gradle builds it. |
| `sbt-plugin/` | The sbt plugin, in Scala, for sbt 1 and sbt 2. sbt builds it. |
| `examples/` | A library and an app that publish to and resolve from a real Cloudsmith repository. |

## Design rules

- **No runtime dependencies.** The extension and the plugins run inside the
  class loader of Maven, Gradle, or sbt. A library added there can conflict
  with a library that a build already uses. The build tools provide their APIs
  and SLF4J at run time. `Json.java` exists for the same
  reason. Logic that needs more belongs in the
  [Cloudsmith CLI](https://github.com/cloudsmith-io/cloudsmith-cli).
- **Java 17 is the minimum.** Gradle 9 and Maven 4 need it too. Raising it
  drops users whose builds run on an older JDK. The sbt plugin compiles the
  Java first, because the Scala 2.12 compiler of sbt 1 cannot read Java
  records.
- **Fail silently.** Every CLI failure makes the extension or plugin step
  aside, so the build behaves as if it were not installed. In the Maven
  extension, `newInstance` throws only `NoTransporterException`. The Gradle and
  sbt plugins throw nothing.
- **Trust few hosts.** The extension runs ahead of the transports that Maven
  ships. That is safe only because it steps aside for every repository that is
  not HTTPS on a host that `cloudsmith domains list` reports. Treat a change to
  that check as a security change.

## Prerequisites

[mise](https://mise.jdx.dev/) installs the pinned Java, Maven, Gradle, sbt,
and tools. Install mise as the
[mise documentation](https://mise.jdx.dev/getting-started.html) describes, then
run:

```sh
git clone git@github.com:cloudsmith-labs/cloudsmith-maven-tools.git
cd cloudsmith-maven-tools
mise install
mise run hooks    # Run the checks before each commit.
```

## Build from source

Install the extension into your local Maven repository, and the plugin into
your local Maven repository for Gradle:

```sh
mvn -f maven-extension/pom.xml install -DskipTests
gradle -p gradle-plugin publishToMavenLocal
(cd sbt-plugin && sbt +publishLocal)
```

For Gradle, add `mavenLocal()` and `gradlePluginPortal()` to
`pluginManagement.repositories` in `settings.gradle.kts`. sbt finds the plugin
in its local repository without changes.

## Test

| Command | What it runs |
|---------|--------------|
| `mise run test` | Unit tests, and the Gradle and sbt integration tests |
| `mise run verify` | Every test, including the Maven integration tests |

The unit tests replace the CLI with fakes and use no network. The integration
tests run real Maven, Gradle, and sbt builds against a local HTTPS server that
requires Basic auth, with a shell script in place of the CLI. The server names
the same realms as Cloudsmith. The sbt tests need the network, because the sbt
launcher downloads sbt. The integration tests do not run on Windows.

The Maven integration tests use the Maven that runs them. The Gradle
integration tests use the Gradle that runs them, unless you set
`it.gradle.home`. To test other versions, as CI does:

```sh
# Maven 4, and the Maven under test on Java 17
mise exec aqua:apache/maven@4.0.0-rc-7 -- mvn -B -f maven-extension/pom.xml verify \
    -Dit.java.home="$(mise where java@temurin-17)"

# Gradle 7.6 on Java 17
mise install gradle@7.6.6 java@temurin-17
gradle -p gradle-plugin test \
    -Pit.gradle.home="$(dirname "$(dirname "$(mise which gradle --tool gradle@7.6.6)")")" \
    -Pit.java.home="$(mise where java@temurin-17)"

# sbt 1.10, with the sbt 1 build of the plugin, and sbt 2.0, with the sbt 2 build
(cd sbt-plugin && sbt -Dit.sbt.version=1.10.11 "++2.12.x" test)
(cd sbt-plugin && sbt -Dit.sbt.version=2.0.10 "++3.x" test)
```

`CloudsmithCliTest` reads
`common/src/test/resources/fixtures/domains_list_v1.json`, the output of a real
`cloudsmith domains list` with no workspace. Record it again when the CLI
contract changes:

```sh
env -u CLOUDSMITH_WORKSPACE -u CLOUDSMITH_ORG HOME="$(mktemp -d)" \
    cloudsmith domains list | python3 -m json.tool \
    > common/src/test/resources/fixtures/domains_list_v1.json
```

The empty `HOME` keeps the custom domains of your own workspace out of the
fixture.

## Run the examples

The examples publish a library to a Cloudsmith repository and then compile an
app against it, with Maven, Gradle, and sbt. See
[examples/README.md](./examples/README.md).

The Examples workflow runs them for each target in the `EXAMPLE_TARGETS`
repository variable, a JSON list. Each target authenticates with GitHub Actions
OIDC, so the repository stores no Cloudsmith token:

```json
[
  {
    "name": "cloudsmith.io hosts",
    "workspace": "WORKSPACE",
    "repository": "REPOSITORY",
    "service": "SERVICE_ACCOUNT_SLUG"
  },
  {
    "name": "custom domain",
    "workspace": "WORKSPACE",
    "repository": "REPOSITORY",
    "service": "SERVICE_ACCOUNT_SLUG",
    "deploy_url": "https://HOST/REPOSITORY/",
    "resolve_url": "https://HOST/basic/REPOSITORY/maven/",
    "resolve_latest": true
  }
]
```

Cloudsmith processes an upload before it serves it, and some workspaces take
longer than the 15 minutes that the examples wait. For such a target, set
`resolve_latest`. The app then resolves the newest version that Cloudsmith has
processed, not the version that the run published.

Each service account needs write access to its repository and an OIDC provider
that trusts this GitHub repository. Without `EXAMPLE_TARGETS`, the workflow
skips the examples.

## Lint and format

[google-java-format](https://github.com/google/google-java-format) formats the
Java code. The Scala code has no formatter yet. [zizmor](https://docs.zizmor.sh/) audits the workflows,
[gitleaks](https://github.com/gitleaks/gitleaks) scans for secrets, and
[typos](https://github.com/crate-ci/typos) checks spelling.
[prek](https://github.com/j178/prek) runs them all from
`.pre-commit-config.yaml`.

```sh
mise run format    # Format every Java file.
mise run lint      # Run every check that the CI Lint job runs.
mise run secrets   # Scan the working tree and the full history for secrets.
```

The `gitleaks` hook scans staged changes only, so it reads nothing during
`mise run lint`. The Security workflow and `mise run secrets` scan everything.

## Commit messages and pull requests

Write commit messages and pull request titles as
[Conventional Commits](https://www.conventionalcommits.org/), for example
`fix: skip repositories without a URL`. release-please builds the version and
the changelog from them:

| Type | Effect on the next release |
|------|----------------------------|
| `feat` | New minor version, listed under Features |
| `fix` | New patch version, listed under Bug Fixes |
| `feat!` or a `BREAKING CHANGE:` footer | New major version (minor before 1.0) |
| `docs`, `refactor`, `test`, `build`, `ci`, `chore` | No release |

Squash-merge pull requests, so that the title becomes the commit message.

Each change in behavior needs a test. To report a security issue, see
[SECURITY.md](./SECURITY.md).
