# Releasing

[release-please](https://github.com/googleapis/release-please) automates
releases. The Maven extension, the Gradle plugin, and the sbt plugin always
share one version.

## How a release happens

1. Pull requests merge to `main` with
   [Conventional Commits](https://www.conventionalcommits.org/) titles. See
   [CONTRIBUTING.md](./CONTRIBUTING.md#commit-messages-and-pull-requests).
2. On each push to `main`, the Release workflow opens or updates a release
   pull request named `chore(main): release X.Y.Z`. It sets the version in
   every file listed in `release-please-config.json` and adds the release notes
   to `CHANGELOG.md`.
3. Review the release pull request, then merge it.
4. The Release workflow creates the `vX.Y.Z` tag and the GitHub release, and
   then runs each publish job that is turned on:

   | Job | Publishes | Runs when |
   |-----|-----------|-----------|
   | GitHub | Every jar, attached to the GitHub release | Always |
   | Cloudsmith | Every jar, and the Gradle plugin marker | `RELEASE_CLOUDSMITH_REPO` is set |
   | Maven Central | The Maven extension and the sbt plugin, signed, with sources and javadoc | `PUBLISH_TO_MAVEN_CENTRAL` is `true` |
   | Gradle Plugin Portal | The Gradle plugin | `PUBLISH_TO_GRADLE_PLUGIN_PORTAL` is `true` |

5. Confirm that each destination shows the new version.

Each publish job runs a mise task, so you can run the same step locally. A
failed job can be run again from the workflow run page.

## Set up GitHub

1. In **Settings > Actions > General**, select
   **Allow GitHub Actions to create and approve pull requests**.
2. Add the `CLOUDSMITH_BOT_PR_TOKEN` secret. release-please opens the release
   pull request with this token, so CI runs on it. Without the secret,
   release-please uses the default token, and CI does not run on the release
   pull request. The Dependabot auto-merge workflow uses the same token.
3. Protect `main` and make the CI checks required. The Dependabot auto-merge
   workflow waits for the required checks.

## Set up Cloudsmith

Each tool authenticates the upload of its own release, with GitHub Actions
OIDC.

1. Create a Cloudsmith Maven repository for releases.
2. Create a service account with write access to the repository.
3. Add a GitHub Actions OIDC provider with the claims `repository` set to this
   GitHub repository and `ref` set to `refs/heads/main`, and map it to the
   service account. The `ref` claim stops other branches from publishing.
4. Set these repository variables:

   | Variable | Value |
   |----------|-------|
   | `RELEASE_CLOUDSMITH_ORG` | The workspace |
   | `RELEASE_CLOUDSMITH_REPO` | The repository |
   | `RELEASE_CLOUDSMITH_SERVICE_SLUG` | The service account slug |

To try it locally against a test repository:

```sh
CLOUDSMITH_ORG=WORKSPACE CLOUDSMITH_REPO=REPOSITORY mise run publish:cloudsmith
```

## Set up Maven Central

1. Sign in to the [Central Portal](https://central.sonatype.com/) and verify
   the `io.cloudsmith` namespace. Verification needs a DNS TXT record on
   `cloudsmith.io`.
2. Generate a user token. Save its username and password as the
   `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` secrets.
3. Create a GPG key for releases, and publish its public key to
   `keyserver.ubuntu.com` and `keys.openpgp.org`. Save the ASCII-armored
   private key as the `MAVEN_GPG_KEY` secret, and its passphrase as
   `MAVEN_GPG_PASSPHRASE`:

   ```sh
   gpg --armor --export-secret-keys KEY_ID
   ```

4. Set the repository variable `PUBLISH_TO_MAVEN_CENTRAL` to `true`.

The Maven build signs with `MAVEN_GPG_KEY`. The sbt build signs with the key
in the gpg keyring, so the workflow imports `MAVEN_GPG_KEY` first. sbt uploads
with its built-in Central Portal support.

To check the signed builds locally without publishing:

```sh
MAVEN_GPG_KEY="$(gpg --armor --export-secret-keys KEY_ID)" MAVEN_GPG_PASSPHRASE=... \
    mvn -f maven-extension/pom.xml -P central verify -DskipTests
(cd sbt-plugin && PGP_PASSPHRASE=... sbt +publishSigned)  # Stages in target/sona-staging.
```

## Set up the Gradle Plugin Portal

1. Sign in to the [Gradle Plugin Portal](https://plugins.gradle.org/) with an
   account that can publish under `io.cloudsmith`. The portal reviews the first
   release of a plugin by hand.
2. Create an API key. Save it as the `GRADLE_PUBLISH_KEY` and
   `GRADLE_PUBLISH_SECRET` secrets.
3. Set the repository variable `PUBLISH_TO_GRADLE_PLUGIN_PORTAL` to `true`.

## Version scheme

This project follows [Semantic Versioning](https://semver.org/). A breaking
change to the behavior of the extension or the plugins, or to their environment
variables, increases the major version. Before 1.0, a breaking change increases
the minor version.
