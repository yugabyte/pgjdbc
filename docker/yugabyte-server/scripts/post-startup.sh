#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

log () { echo "$@" 1>&2; }
err () { echo "$@" 1>&2; exit 1; }

is_option_enabled ()  { [[ "${1:-}" == "true" ]] || [[ "${1:-}" == "on" ]] || [[ "${1:-}" == "yes" ]]; }

SCRAM="${SCRAM:-yes}"
YB_BIN="/home/yugabyte/bin"
# Must match the container hostname set in docker-compose.yml
YB_HOST="${HOSTNAME:-yugabytedb}"

# Run SQL as the yugabyte superuser against a specific database
ysql () {
    local database="${1}"
    local sql="${2}"
    log "  [${database}] ${sql}"
    "${YB_BIN}/ysqlsh" \
        -h "${YB_HOST}" \
        -p 5433 \
        -U yugabyte \
        -d "${database}" \
        -v ON_ERROR_STOP=1 \
        <<<"${sql}"
}

# Run SQL, but continue even if the statement fails (e.g. "already exists")
ysql_try () {
    local database="${1}"
    local sql="${2}"
    log "  [${database}] (try) ${sql}"
    "${YB_BIN}/ysqlsh" \
        -h "${YB_HOST}" \
        -p 5433 \
        -U yugabyte \
        -d "${database}" \
        <<<"${sql}" 2>&1 || true
}

# ---------------------------------------------------------------------------
# Determine password encryption method
# ---------------------------------------------------------------------------
get_password_encryption () {
    if is_option_enabled "${SCRAM}"; then
        printf "scram-sha-256"
    else
        printf "md5"
    fi
}

password_encryption="$(get_password_encryption)"

# ---------------------------------------------------------------------------
# 1. Ensure superuser "postgres" exists for tests using privilegedUser=postgres
#    Many tests open a privileged connection with user "postgres". In YugabyteDB
#    the default superuser is "yugabyte", so we create a "postgres" alias.
# ---------------------------------------------------------------------------
log "Creating superuser 'postgres' for test compatibility..."
ysql_try "yugabyte" "CREATE ROLE postgres SUPERUSER LOGIN PASSWORD '' ;"

# ---------------------------------------------------------------------------
# 2. Create the primary test user "test" with password "test"
# ---------------------------------------------------------------------------
log "Creating test user..."
ysql "yugabyte" "
    SET password_encryption='${password_encryption}';
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'test') THEN
            CREATE USER test WITH PASSWORD 'test' REPLICATION;
        END IF;
    END
    \$\$;
"

# ---------------------------------------------------------------------------
# 3. Create primary test databases
# ---------------------------------------------------------------------------
log "Creating database 'test'..."
ysql_try "yugabyte" "CREATE DATABASE test OWNER test;"

log "Creating database 'test_2'..."
ysql_try "yugabyte" "CREATE DATABASE test_2 OWNER test;"

# Grant test user access to the default yugabyte database as well
ysql_try "yugabyte" "GRANT ALL PRIVILEGES ON DATABASE yugabyte TO test;"

# ---------------------------------------------------------------------------
# 4. Install hstore extension
#    hstore is pre-bundled with YugabyteDB. Tests check for it at runtime
#    via isHStoreEnabled(), so it must exist in the databases under test.
# ---------------------------------------------------------------------------
log "Installing hstore extension..."
for db in yugabyte test; do
    ysql_try "${db}" "CREATE EXTENSION IF NOT EXISTS hstore;"
done

# ---------------------------------------------------------------------------
# 5. Create SSL test databases and install sslinfo
#    These databases are required by ssl/ tests in the test suite.
#    sslinfo may not be available in all YugabyteDB builds; we try gracefully.
# ---------------------------------------------------------------------------
SSL_DBS=(hostdb hostssldb hostnossldb certdb hostsslcertdb)

log "Creating SSL test databases..."
for db_name in "${SSL_DBS[@]}"; do
    ysql_try "yugabyte" "CREATE DATABASE ${db_name};"

    # Grant the test user connect privileges
    ysql_try "yugabyte" "GRANT ALL PRIVILEGES ON DATABASE ${db_name} TO test;"

    # sslinfo lets tests verify whether a connection is using SSL.
    # It may not be bundled in all YugabyteDB versions; failures are non-fatal.
    log "  Attempting to install sslinfo in '${db_name}' (non-fatal if unavailable)..."
    ysql_try "${db_name}" "CREATE EXTENSION IF NOT EXISTS sslinfo;"
done

# ---------------------------------------------------------------------------
# 6. SCRAM-specific: update pg_hba entries rely on YugabyteDB's own auth
#    infrastructure. Nothing extra to do here — the hba_conf_csv was already
#    set in entrypoint.sh. We just confirm the user password encryption matches.
# ---------------------------------------------------------------------------
if is_option_enabled "${SCRAM}"; then
    log "SCRAM-SHA-256 authentication is enabled."
else
    log "MD5 authentication is enabled."
fi

log "Post-startup initialization complete."
