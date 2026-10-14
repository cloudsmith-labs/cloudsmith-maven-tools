#!/usr/bin/env bash
# Publishes the example library to Cloudsmith, then compiles the example app against the copy in
# Cloudsmith. Usage: examples/run.sh maven|gradle|sbt
set -euo pipefail

tool="${1:?Usage: examples/run.sh maven|gradle|sbt}"
: "${CLOUDSMITH_ORG:?Set CLOUDSMITH_ORG to your Cloudsmith workspace.}"
: "${CLOUDSMITH_REPO:?Set CLOUDSMITH_REPO to a Cloudsmith repository that you can write to.}"
# Cloudsmith rejects a release version that it already has, so each run publishes a new one.
export EXAMPLE_VERSION="${EXAMPLE_VERSION:-0.1.$(date +%s)}"
cd "$(dirname "$0")"

# Cloudsmith processes an upload before it serves it, so a new version can return 404 for several
# minutes.
retry() {
  for attempt in $(seq 1 30); do
    if "$@"; then
      return 0
    fi
    echo "Attempt ${attempt} failed. Waiting 30 seconds for Cloudsmith to process the upload."
    sleep 30
  done
  return 1
}

# The version that the app resolves: the version that this run published, or, with
# EXAMPLE_RESOLVE_LATEST set, the newest version that Cloudsmith has processed. Some workspaces take
# much longer than others to process an upload.
resolve_version() {
  local latest=""
  if [ -n "${EXAMPLE_RESOLVE_LATEST:-}" ]; then
    latest="$(cloudsmith ls pkgs "${CLOUDSMITH_ORG}/${CLOUDSMITH_REPO}" -F json -l 1 --sort=-date \
      -q "name:^example-${tool}$ AND status:completed" | jq -r '.data[0].version // empty')"
  fi
  echo "${latest:-${EXAMPLE_VERSION}}"
}

run_maven() {
  mvn -B -q -f ../maven-extension/pom.xml install -DskipTests

  # An empty local repository makes Maven fetch every plugin and dependency from Cloudsmith.
  local_repository="$(mktemp -d)"
  cp library/pom.xml library/pom.xml.orig
  trap 'mv library/pom.xml.orig library/pom.xml; rm -rf "${local_repository}"' EXIT
  sed -i.bak "s#<version>0.1.0</version>#<version>${EXAMPLE_VERSION}</version>#" library/pom.xml
  rm library/pom.xml.bak
  mvn -B -f library/pom.xml deploy \
    ${CLOUDSMITH_DEPLOY_URL:+"-Dcloudsmith.deploy.url=${CLOUDSMITH_DEPLOY_URL}"}

  mkdir -p "${local_repository}/io/cloudsmith"
  cp -R "${HOME}/.m2/repository/io/cloudsmith/cloudsmith-maven-extension" \
    "${local_repository}/io/cloudsmith/"
  retry mvn -B -f app/pom.xml compile -U \
    -Dmaven.repo.local="${local_repository}" \
    -Dexample.version="$(resolve_version)" \
    ${CLOUDSMITH_RESOLVE_URL:+"-Dcloudsmith.resolve.url=${CLOUDSMITH_RESOLVE_URL}"}
}

run_gradle() {
  gradle :library:publish \
    ${CLOUDSMITH_DEPLOY_URL:+"-Pcloudsmith.deploy.url=${CLOUDSMITH_DEPLOY_URL}"}
  # Publishing does not put the library in the Gradle cache, so Gradle downloads it from
  # Cloudsmith.
  EXAMPLE_VERSION="$(resolve_version)" retry gradle :app:compileJava --refresh-dependencies \
    ${CLOUDSMITH_RESOLVE_URL:+"-Pcloudsmith.resolve.url=${CLOUDSMITH_RESOLVE_URL}"}
}

run_sbt() {
  sbt --server -batch library/publish \
    ${CLOUDSMITH_DEPLOY_URL:+"-Dcloudsmith.deploy.url=${CLOUDSMITH_DEPLOY_URL}"}
  EXAMPLE_VERSION="$(resolve_version)" retry sbt --server -batch app/compile \
    ${CLOUDSMITH_RESOLVE_URL:+"-Dcloudsmith.resolve.url=${CLOUDSMITH_RESOLVE_URL}"}
}

case "${tool}" in
  maven) run_maven ;;
  gradle) run_gradle ;;
  sbt) run_sbt ;;
  *) echo "Usage: examples/run.sh maven|gradle|sbt" >&2; exit 2 ;;
esac
echo "Published example-${tool} ${EXAMPLE_VERSION}, and resolved example-${tool} $(resolve_version) from Cloudsmith"
