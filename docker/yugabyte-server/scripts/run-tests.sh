#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# run-tests.sh — executed inside the test-runner container.
#
# Responsibilities:
#   1. Install required system packages (git, procps)
#   2. Write build.local.properties and ssltest.local.properties so tests
#      connect to the YugabyteDB container without any manual configuration.
#   3. Run the full Gradle test suite.
# ---------------------------------------------------------------------------

log () { echo "==> $*" 1>&2; }

PGJDBC_DIR="/pgjdbc"
YB_HOST="${YB_HOST:-yugabytedb}"
YB_PORT="${YB_PORT:-5433}"
SSL_ENABLED="${SSL:-yes}"

# ---------------------------------------------------------------------------
# 1. Install system tools needed during Gradle execution
#    - git: required by the vlsi-release-plugins for git metadata
#    - procps: provides 'ps', used by some Gradle internals
# ---------------------------------------------------------------------------
log "Installing system packages..."
apt-get update -qq && apt-get install -y --no-install-recommends git procps >/dev/null 2>&1
log "System packages installed."

# ---------------------------------------------------------------------------
# 2. Configure git safe directory (Gradle version plugins read git metadata)
# ---------------------------------------------------------------------------
git config --global --add safe.directory "${PGJDBC_DIR}"

# ---------------------------------------------------------------------------
# 3. Write build.local.properties
#
#    Connection parameters point to the yugabytedb service on the Docker
#    Compose network.  We use the YugabyteDB built-in superuser "yugabyte"
#    for both normal and privileged connections so that CREATE EXTENSION and
#    other superuser DDL works.
#
#    Note on 'database': YugabyteDB ships with a default database called
#    'yugabyte'.  The build.properties already defaults to database=yugabyte
#    so we do not need to override it here, but we do so explicitly for
#    clarity and to ensure build.local.properties wins over build.properties.
#
#    Note on 'secondaryServer*/secondaryPort*': Multi-host tests
#    (MultiHostsConnectionTest) attempt to connect to these and skip
#    gracefully when they are unreachable, so we leave them pointing at the
#    same host which means the secondary-connection attempt will succeed but
#    secondary-only behaviour tests will still self-skip via assumeTrue.
# ---------------------------------------------------------------------------
log "Writing build.local.properties..."
cat > "${PGJDBC_DIR}/build.local.properties" <<EOF
server=${YB_HOST}
port=${YB_PORT}
database=yugabyte
username=yugabyte
password=yugabyte
privilegedUser=yugabyte
privilegedPassword=yugabyte
# Point secondaries at the same host so MultiHostsConnectionTest skips
# gracefully via assumeTrue(isReplicationInstanceAvailable()) instead of
# failing with a hard connection error.
secondaryServer1=${YB_HOST}
secondaryPort1=${YB_PORT}
secondaryServer2=${YB_HOST}
secondaryPort2=${YB_PORT}
preparethreshold=5
protocolVersion=0
EOF
log "build.local.properties written."

# ---------------------------------------------------------------------------
# 4. Write ssltest.local.properties
#
#    'certdir' is a path relative to the project root as resolved by
#    TestUtil.getFile().  The default value "certdir" already resolves to
#    <project_root>/certdir which is mounted at /pgjdbc/certdir, so we keep
#    the same value.
#
#    enable_ssl_tests=true activates the ssl/ test package.  We disable it
#    when the YugabyteDB container was started without SSL (SSL=no).
# ---------------------------------------------------------------------------
log "Writing ssltest.local.properties..."
if [[ "${SSL_ENABLED}" == "yes" || "${SSL_ENABLED}" == "true" || "${SSL_ENABLED}" == "on" ]]; then
    ssl_tests_enabled="true"
else
    ssl_tests_enabled="false"
fi

cat > "${PGJDBC_DIR}/ssltest.local.properties" <<EOF
enable_ssl_tests=${ssl_tests_enabled}
certdir=certdir
EOF
log "ssltest.local.properties written."

# ---------------------------------------------------------------------------
# 5. Run the test suite
#
#    Flags used:
#      --no-daemon      : avoids leaving Gradle daemons that outlive the
#                         container and waste memory
#      --no-parallel    : Gradle project-level parallelism off; test-level
#                         parallelism is still on (configured in test-junit5)
#      jandex           : build the Jandex index (required before test)
#      test             : run all tests
#
#    We do NOT pass -PskipReplicationTests because replication tests skip
#    themselves at runtime via @DisabledIfServerVersionBelow / assumeTrue
#    when the YugabyteDB server does not support pg WAL replication.
# ---------------------------------------------------------------------------
log "Starting Gradle test run..."
cd "${PGJDBC_DIR}"

# PGJDBC_GRADLE_EXTRA may contain additional flags injected by the Python
# runner (e.g. --excluded-tests, --tests, --fail-fast).  We split it on
# whitespace into an array so each flag becomes a separate argv element.
read -r -a GRADLE_EXTRA_ARGS <<< "${PGJDBC_GRADLE_EXTRA:-}"

./gradlew \
    --no-daemon \
    --no-parallel \
    jandex test \
    "-Djdbc.drivers=org.postgresql.Driver" \
    "${GRADLE_EXTRA_ARGS[@]+"${GRADLE_EXTRA_ARGS[@]}"}"

log "Test run complete."
