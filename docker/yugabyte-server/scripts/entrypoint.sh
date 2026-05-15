#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

log () { echo "$@" 1>&2; }
err () { echo "$@" 1>&2; exit 1; }

is_option_enabled ()  { [[ "${1:-}" == "true" ]] || [[ "${1:-}" == "on" ]] || [[ "${1:-}" == "yes" ]]; }
is_option_disabled () { [[ "${1:-}" == "false" ]] || [[ "${1:-}" == "off" ]] || [[ "${1:-}" == "no" ]]; }

# ---------------------------------------------------------------------------
# Environment defaults
# ---------------------------------------------------------------------------
SSL="${SSL:-no}"
XA="${XA:-yes}"
SCRAM="${SCRAM:-yes}"
# YSQL Connection Manager (Odyssey). Use 1/yes/true to enable.
CONN_MGR="${YB_ENABLE_YSQL_CONN_MGR:-${CONN_MGR:-no}}"
TZ="${TZ:-Etc/UTC}"

YB_BIN="/home/yugabyte/bin"
CERT_SRC="/custom/certdir"
CERT_DIR="/home/certdir"

# YugabyteDB 2.25 binds the YSQL postgres process to the container hostname
# (not to 127.0.0.1), so ysqlsh must connect via the hostname, not loopback.
# The hostname is set in docker-compose as `hostname: yugabytedb`.
YB_HOST="${HOSTNAME:-yugabytedb}"

# ---------------------------------------------------------------------------
# 1. Build tserver_flags
#
#    IMPORTANT: YugabyteDB 2.25 parses --tserver_flags as comma-separated
#    key=value pairs. When a flag value is itself a comma-separated list
#    (like ysql_pg_conf_csv), wrap it in {curly braces} so the outer
#    parser does not split on the inner commas. This was verified to work
#    with YugabyteDB 2.25 (based on PostgreSQL 15).
#
#    SSL via `ssl=on` in ysql_pg_conf_csv is NOT supported in YugabyteDB 2.25
#    (the server fails to start). YugabyteDB uses its own TLS stack for client
#    connections controlled by use_client_to_server_encryption and
#    certs_for_client_dir. See SSL_NOTES below for enabling SSL separately.
# ---------------------------------------------------------------------------

pg_conf_items=()

if is_option_enabled "${XA}"; then
    pg_conf_items+=("max_prepared_transactions=64")
fi

# Build ysql_pg_conf_csv with curly-brace wrapper to protect inner commas
tserver_flags=""

if [[ ${#pg_conf_items[@]} -gt 0 ]]; then
    pg_conf_csv=$(IFS=,; echo "${pg_conf_items[*]}")
    tserver_flags="ysql_pg_conf_csv={${pg_conf_csv}}"
fi

# ---------------------------------------------------------------------------
# 2. Prepare SSL certs
#    We copy them now (even if SSL=no) so post-startup can install sslinfo
#    in the SSL test databases if desired.
# ---------------------------------------------------------------------------
if is_option_enabled "${SSL}"; then
    log "SSL=yes: preparing certificates..."
    cp -r "${CERT_SRC}" "${CERT_DIR}"
    chown -R root:root "${CERT_DIR}"
    chmod 0600 "${CERT_DIR}"/*.key

    # YugabyteDB 2.25 uses its own TLS stack: use_client_to_server_encryption
    # with certs named node.<hostname>.crt / node.<hostname>.key / ca.crt.
    # We rename the pgjdbc server certs accordingly.
    cp "${CERT_DIR}/root.crt"   "${CERT_DIR}/ca.crt"
    cp "${CERT_DIR}/server.crt" "${CERT_DIR}/node.yugabytedb.crt"
    cp "${CERT_DIR}/server.key" "${CERT_DIR}/node.yugabytedb.key"
    chmod 0600 "${CERT_DIR}/node.yugabytedb.key"

    # Append SSL tserver flags
    ssl_flags="use_client_to_server_encryption=true,certs_for_client_dir=${CERT_DIR},cert_node_filename=yugabytedb"
    if [[ -n "${tserver_flags}" ]]; then
        tserver_flags="${tserver_flags},${ssl_flags}"
    else
        tserver_flags="${ssl_flags}"
    fi
fi

# ---------------------------------------------------------------------------
# 2b. YSQL Connection Manager (Odyssey)
#     When enabled, each tserver runs a connection manager process for
#     connection pooling. Pass enable_ysql_conn_mgr=true in tserver_flags.
# ---------------------------------------------------------------------------
if is_option_enabled "${CONN_MGR}"; then
    log "YSQL Connection Manager enabled (CONN_MGR/YB_ENABLE_YSQL_CONN_MGR=yes)."
    if [[ -n "${tserver_flags}" ]]; then
        tserver_flags="${tserver_flags},enable_ysql_conn_mgr=true"
    else
        tserver_flags="enable_ysql_conn_mgr=true"
    fi
fi

# ---------------------------------------------------------------------------
# 3. Start yugabyted
# ---------------------------------------------------------------------------
log "Starting yugabyted..."
if [[ -n "${tserver_flags}" ]]; then
    log "  tserver_flags: ${tserver_flags}"
    "${YB_BIN}/yugabyted" start \
        --daemon=true \
        --tserver_flags="${tserver_flags}"
else
    "${YB_BIN}/yugabyted" start \
        --daemon=true
fi

# ---------------------------------------------------------------------------
# 4. Wait for YSQL to become ready
# ---------------------------------------------------------------------------
log "Waiting for YSQL to become ready on port 5433 (host: ${YB_HOST})..."
max_attempts=90
attempt=0
while ! "${YB_BIN}/ysqlsh" -h "${YB_HOST}" -p 5433 -U yugabyte -c "SELECT 1" >/dev/null 2>&1; do
    attempt=$(( attempt + 1 ))
    if [[ ${attempt} -ge ${max_attempts} ]]; then
        log "=== Collecting diagnostics ==="
        "${YB_BIN}/yugabyted" status 2>&1 || true
        err "YSQL did not become ready after ${max_attempts} attempts. Giving up."
    fi
    log "  Waiting for YSQL on ${YB_HOST}:5433... attempt ${attempt}/${max_attempts}"
    sleep 3
done
log "YSQL is ready on ${YB_HOST}:5433."

# ---------------------------------------------------------------------------
# 5. Write custom HBA rules
#
#    Default YugabyteDB 2.25 HBA is: host all all all trust
#    We override it with specific rules to enable:
#      - Password (MD5/SCRAM) auth for the 'test' user and SSL databases
#      - Trust for the superuser 'yugabyte' and 'postgres' compatibility alias
#      - SSL-specific rules when SSL=yes
#
#    The HBA file path is /root/var/data/pg_data/ysql_hba.conf
#    (default yugabyted data directory is /root/var).
#    pg_reload_conf() applies the changes without a server restart.
# ---------------------------------------------------------------------------
YB_HBA_FILE="/root/var/data/pg_data/ysql_hba.conf"

log "Writing custom HBA rules to ${YB_HBA_FILE}..."
{
    echo "# Generated by yugabyte-server entrypoint.sh"
    echo ""
    echo "# Superuser access - trust (no password needed for admin operations)"
    echo "local all yugabyte                        trust"
    echo "host  all yugabyte    0.0.0.0/0           trust"
    echo "local all postgres                        trust"
    echo "host  all postgres    0.0.0.0/0           trust"
    echo ""
    echo "# Primary test user with password auth"
    if is_option_enabled "${SCRAM}"; then
        echo "host  test all        0.0.0.0/0           scram-sha-256"
        echo "host  test_2 all      0.0.0.0/0           scram-sha-256"
        echo "host  yugabyte test   0.0.0.0/0           scram-sha-256"
        echo "host  hostdb all      0.0.0.0/0           scram-sha-256"
    else
        echo "host  test all        0.0.0.0/0           md5"
        echo "host  test_2 all      0.0.0.0/0           md5"
        echo "host  yugabyte test   0.0.0.0/0           md5"
        echo "host  hostdb all      0.0.0.0/0           md5"
    fi
    echo ""
    if is_option_enabled "${SSL}"; then
        echo "# SSL-specific databases"
        echo "hostnossl  hostnossldb  all  0.0.0.0/0  md5"
        echo "hostssl    hostssldb    all  0.0.0.0/0  md5"
        echo "hostssl    hostsslcertdb all 0.0.0.0/0  md5 clientcert=verify-full"
        echo "hostssl    certdb       all  0.0.0.0/0  cert"
        echo ""
    fi
    echo "# Catch-all for all other connections (e.g. test_2 replication conn)"
    echo "local all all                             trust"
    echo "host  all all         0.0.0.0/0           trust"
} > "${YB_HBA_FILE}"

log "Reloading HBA config..."
"${YB_BIN}/ysqlsh" -h "${YB_HOST}" -p 5433 -U yugabyte \
    -c "SELECT pg_reload_conf();" >/dev/null
log "HBA config reloaded."

# ---------------------------------------------------------------------------
# 6. Run post-startup initialization (create users, databases, extensions)
# ---------------------------------------------------------------------------
log "Running post-startup initialization..."
bash /custom/scripts/post-startup.sh

# ---------------------------------------------------------------------------
# 7. Keep container alive so healthchecks and tests can connect
# ---------------------------------------------------------------------------
log "YugabyteDB initialization complete. Container is ready."
log ""
log "  YSQL port  : 5433"
log "  SSL        : ${SSL}"
log "  XA         : ${XA}"
log "  SCRAM      : ${SCRAM}"
log "  Conn mgr   : ${CONN_MGR}"
log ""
log "SSL_NOTES: To enable SSL tests, set SSL=yes. YugabyteDB uses its own"
log "  TLS mechanism (use_client_to_server_encryption). Certs from certdir/"
log "  are renamed to node.yugabytedb.crt/key/ca.crt format. The JDBC"
log "  client must use sslmode=verify-ca (not verify-full) because the server"
log "  cert CN=localhost does not match the Docker hostname 'yugabytedb'."
log ""
exec tail -f /dev/null
