#!/usr/bin/env python3
"""
pgjdbc Docker Test Runner
---------------------------------------------------------------------
Runs YugabyteDB in one container and the pgjdbc (jdbc-yugabytedb) Gradle
test suite in another container, using the docker/yugabyte-server/
infrastructure that lives alongside this script.

Architecture
============
  ┌─────────────────────────┐   JDBC 5433   ┌─────────────────────────┐
  │  yugabytedb container   │ ◄──────────── │  test-runner container  │
  │  yugabytedb/yugabyte    │               │  eclipse-temurin:17-jdk │
  │  entrypoint.sh          │               │  ./gradlew jandex test  │
  └─────────────────────────┘               └─────────────────────────┘

Setup (mirrors docker/yugabyte-server/docker-compose.yml):
  - YugabyteDB container is started directly (not via Compose) so this
    script has full control over lifecycle and port mapping.
  - Entrypoint (docker/yugabyte-server/scripts/entrypoint.sh) starts
    yugabyted, waits for YSQL, writes HBA rules, creates users/databases
    and extensions.
  - The test-runner container mounts this repo at /pgjdbc and runs
    docker/yugabyte-server/scripts/run-tests.sh, which writes
    build.local.properties / ssltest.local.properties and launches Gradle.

Environment variables
=====================
  YB_IMAGE               YugabyteDB Docker image            default: yugabytedb/yugabyte:latest
  SSL                    Enable YugabyteDB client TLS        default: no
  XA                     Enable XA / prepared transactions   default: yes
  SCRAM                  Use scram-sha-256 auth for test user default: yes
  YB_ENABLE_YSQL_CONN_MGR Enable YSQL Connection Manager    default: no (set 1/yes/true to enable)
  PGJDBC_DIR             Path to pgjdbc source root          default: directory of this script
  PGJDBC_TEST_OUTPUT_LOG Path to log file                    default: <PGJDBC_DIR>/pgjdbc_test_output[_connmgr].log
  YB_WAIT_SEC            Seconds to sleep before polling YB  default: 50
  YB_VERIFY_RETRIES      Max YSQL readiness poll retries     default: 30
  TEST_TIMEOUT_SEC       Hard cap on the whole test run (s)  default: 5400 (90 min); 0 = no cap
  PGJDBC_STOP_ON_FIRST_FAILURE  Stop Gradle after first failure  default: 0 (false)
  PGJDBC_GRADLE_TESTS    Extra --tests filter passed to Gradle  default: (all tests)

SSL
===
  When SSL=yes, the YugabyteDB container enables TLS using certdir/server certs
  (renamed to node.yugabytedb.crt/key and ca.crt). sslmode=require and
  sslmode=verify-ca work. sslmode=verify-full may fail because the server cert
  typically has CN=localhost while the test hostname is yugabytedb.

Usage
=====
  python run_pgjdbc_docker_tests.py               # normal run
  python run_pgjdbc_docker_tests.py --exclude-until-pass   # auto-exclude failing tests and re-run
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Fully-qualified test method patterns excluded by default when running
# against YugabyteDB. These are passed as
#     ./gradlew test --excluded-tests '<pattern>'
# Patterns follow Gradle's test filtering syntax:
#     org.postgresql.test.SomeClass.someMethod
# Use '*' as a wildcard. An entry of the form 'ClassName.*' excludes all
# methods in that class.
# ---------------------------------------------------------------------------
EXCLUDED_TESTS: list[str] = []


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _run(cmd: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    """Run a subprocess, streaming output by default."""
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)


def _run_quiet(cmd: list[str]) -> subprocess.CompletedProcess:
    """Run a subprocess, ignoring failures and suppressing output."""
    return subprocess.run(cmd, capture_output=True, text=True)


def _strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*m", "", text)


# ---------------------------------------------------------------------------
# Failure parsing
# ---------------------------------------------------------------------------

def extract_failures_from_output(output: str) -> list[tuple[str, str]]:
    """
    Parse Gradle JUnit test output for failure lines.

    Gradle (with showStandardStreams=true) emits two formats:

    1. Verbose method-level:   ClassName > methodName() FAILED
    2. Class-level summary:
         FAILURE   0.5sec,   15 completed, 15 failed, …  ClassName

    Returns list of (identifier, context_message) tuples.
    """
    failures: list[tuple[str, str]] = []
    seen: set[str] = set()
    lines = output.splitlines()

    for line in lines:
        plain = _strip_ansi(line).strip()

        # Gradle verbose per-method: "ClassName > methodName() FAILED"
        m = re.match(r"^(\S+)\s+>\s+(\S+)\s+FAILED\s*$", plain)
        if m:
            ident = f"{m.group(1)}.{m.group(2).rstrip('()')}"
            if ident not in seen:
                seen.add(ident)
                failures.append((ident, ""))
            continue

        # Gradle class-level summary line from test-base.gradle.kts logging:
        # "FAILURE   0.5sec,   N completed, M failed, …, FullClassName"
        m = re.match(r"^FAILURE\s+.*,\s+([\w.]+)\s*$", plain)
        if m:
            ident = m.group(1)
            if ident not in seen:
                seen.add(ident)
                failures.append((ident, "class-level failure (see log)"))
            continue

        # > Task :module:test FAILED
        if plain.startswith("> Task") and "FAILED" in plain:
            if "> Task" not in seen:
                seen.add("> Task")
                failures.append((plain, ""))
            continue

    return failures


def extract_test_name_for_exclusion(identifier: str) -> str | None:
    """
    Convert a failure identifier to a Gradle --excluded-tests pattern.

    Input examples:
      org.postgresql.test.jdbc2.BatchExecuteTest.testBatch → same
      org.postgresql.test.jdbc2.BatchExecuteTest           → append .*
    """
    # Already fully qualified with method
    if re.match(r"^[\w.]+\.\w+$", identifier):
        return identifier
    # Class-level: append wildcard
    if re.match(r"^[\w.]+$", identifier):
        return f"{identifier}.*"
    return None


def add_excluded_test(test_pattern: str) -> bool:
    """Append test_pattern to EXCLUDED_TESTS in this file. Returns True if added."""
    runner_path = Path(__file__).resolve()
    content = runner_path.read_text(encoding="utf-8")
    start = content.find("EXCLUDED_TESTS")
    if start == -1:
        return False
    bracket = content.find("[", start)
    if bracket == -1:
        return False
    block_end = content.find("\n]", bracket)
    if block_end == -1:
        return False
    block = content[bracket:block_end]
    if f'"{test_pattern}"' in block:
        return False
    new_content = content[:block_end] + f'    "{test_pattern}",\n' + content[block_end:]
    runner_path.write_text(new_content, encoding="utf-8")
    EXCLUDED_TESTS.append(test_pattern)
    return True


def _build_excluded_args() -> list[str]:
    """Return list of --excluded-tests arguments for Gradle."""
    args = []
    for pattern in EXCLUDED_TESTS:
        args += ["--excluded-tests", pattern]
    return args


# ---------------------------------------------------------------------------
# Main runner
# ---------------------------------------------------------------------------

def run_docker_tests() -> int:
    script_dir = Path(__file__).resolve().parent
    pgjdbc_dir = Path(os.environ.get("PGJDBC_DIR", str(script_dir)))

    # Config from env
    yb_image        = os.environ.get("YB_IMAGE", "yugabytedb/yugabyte:latest")
    ssl             = os.environ.get("SSL", "no")
    xa              = os.environ.get("XA", "yes")
    scram           = os.environ.get("SCRAM", "yes")
    conn_mgr        = os.environ.get("YB_ENABLE_YSQL_CONN_MGR", "0").lower() in ("1", "true", "yes")
    yb_wait_sec     = int(os.environ.get("YB_WAIT_SEC", "50"))
    verify_retries  = int(os.environ.get("YB_VERIFY_RETRIES", "30"))
    test_timeout    = int(os.environ.get("TEST_TIMEOUT_SEC", "5400"))
    stop_on_fail    = os.environ.get("PGJDBC_STOP_ON_FIRST_FAILURE", "0").lower() in ("1", "true", "yes")
    extra_tests     = os.environ.get("PGJDBC_GRADLE_TESTS", "")

    conn_mgr_suffix = "_connmgr" if conn_mgr else "_noconnmgr"
    default_log     = str(pgjdbc_dir / f"pgjdbc_test_output{conn_mgr_suffix}.log")
    log_file_path   = os.environ.get("PGJDBC_TEST_OUTPUT_LOG", default_log)

    network_name    = "pgjdbc-test-net"
    yb_container    = "pgjdbc-yugabytedb"
    runner_name     = "pgjdbc-test-runner"

    certdir_host    = str(pgjdbc_dir / "certdir" / "server")
    scripts_host    = str(pgjdbc_dir / "docker" / "yugabyte-server" / "scripts")

    conn_mgr_env = "yes" if conn_mgr else "no"
    print("=== pgjdbc Docker Test Runner ===")
    print(f"pgjdbc dir    : {pgjdbc_dir}")
    print(f"YugabyteDB    : {yb_image}")
    print(f"SSL           : {ssl}")
    print(f"XA            : {xa}")
    print(f"SCRAM         : {scram}")
    print(f"Conn mgr      : {conn_mgr_env}  (YB_ENABLE_YSQL_CONN_MGR)")
    print(f"Log file      : {log_file_path}")
    if test_timeout > 0:
        print(f"Test timeout  : {test_timeout}s  (set TEST_TIMEOUT_SEC=0 to disable)")
    if EXCLUDED_TESTS:
        print(f"Excluded tests: {len(EXCLUDED_TESTS)}")
    print()

    # ------------------------------------------------------------------
    # [0/6] Cleanup
    # ------------------------------------------------------------------
    print("[0/6] Cleaning up any leftover containers / network...")
    _run_quiet(["docker", "rm", "-f", runner_name, yb_container])
    _run_quiet(["docker", "network", "rm", network_name])
    print()

    # ------------------------------------------------------------------
    # [1/6] Docker network
    # ------------------------------------------------------------------
    print(f"[1/6] Creating Docker network '{network_name}'...")
    _run(["docker", "network", "create", network_name])
    print()

    # ------------------------------------------------------------------
    # [2/6] YugabyteDB container
    #
    # Mirrors the 'yugabytedb' service in docker-compose.yml.
    # The entrypoint.sh starts yugabyted, waits for YSQL, writes the
    # HBA config, then creates users / databases / extensions.
    # ------------------------------------------------------------------
    print(f"[2/6] Starting YugabyteDB container '{yb_container}'...")
    _run([
        "docker", "run", "-d",
        "--name", yb_container,
        "--hostname", "yugabytedb",
        "--network", network_name,
        "--security-opt", "seccomp:unconfined",
        "-p", "5433:5433",
        "-p", "7000:7000",
        "-p", "9000:9000",
        "-v", f"{scripts_host}:/custom/scripts:ro",
        "-v", f"{certdir_host}:/custom/certdir:ro",
        "-e", f"SSL={ssl}",
        "-e", f"XA={xa}",
        "-e", f"SCRAM={scram}",
        "-e", f"YB_ENABLE_YSQL_CONN_MGR={conn_mgr_env}",
        yb_image,
        "bash", "/custom/scripts/entrypoint.sh",
    ])
    print()

    # ------------------------------------------------------------------
    # [3/6] Wait for YSQL readiness
    # ------------------------------------------------------------------
    print(f"[3/6] Sleeping {yb_wait_sec}s, then polling YSQL readiness "
          f"(up to {verify_retries} retries)...")
    time.sleep(yb_wait_sec)
    for attempt in range(1, verify_retries + 1):
        result = subprocess.run(
            [
                "docker", "exec", yb_container,
                "/home/yugabyte/bin/ysqlsh",
                "-h", "yugabytedb", "-p", "5433", "-U", "yugabyte",
                "-c", "SELECT 1;",
            ],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            print("YSQL is ready.")
            break
        if attempt < verify_retries:
            print(f"  YSQL not ready ({attempt}/{verify_retries}), retrying in 5s...")
            time.sleep(5)
        else:
            print("ERROR: YSQL did not become ready in time.")
            print(result.stderr or result.stdout)
            _run_quiet(["docker", "rm", "-f", yb_container])
            _run_quiet(["docker", "network", "rm", network_name])
            return 1
    print()

    # ------------------------------------------------------------------
    # [4/6] Verify initialization completed (post-startup creates test DBs)
    # ------------------------------------------------------------------
    print("[4/6] Verifying post-startup initialization (databases, users)...")
    verify = subprocess.run(
        [
            "docker", "exec", yb_container,
            "/home/yugabyte/bin/ysqlsh",
            "-h", "yugabytedb", "-p", "5433", "-U", "yugabyte",
            "-c",
            "SELECT datname FROM pg_database "
            "WHERE datname IN ('test','test_2','hostdb','hostssldb','hostnossldb','certdb','hostsslcertdb') "
            "ORDER BY datname;",
        ],
        capture_output=True, text=True,
    )
    if verify.returncode != 0:
        print("WARNING: Could not query databases — initialization may still be in progress.")
        print(verify.stderr or verify.stdout)
    else:
        # Count how many expected databases were found
        found = len([ln for ln in verify.stdout.splitlines() if ln.strip() and not ln.startswith("-") and "datname" not in ln and "(" not in ln])
        print(f"  Found {found}/7 expected test databases.")
    print()

    # ------------------------------------------------------------------
    # [5/6] Run tests
    # ------------------------------------------------------------------
    print("[5/6] Running pgjdbc tests (streaming output + writing log)...")
    print(f"       Log: {log_file_path}")
    print()

    # Build Gradle extra args
    gradle_extra: list[str] = []
    if stop_on_fail:
        gradle_extra += ["--fail-fast"]
    for pattern in EXCLUDED_TESTS:
        gradle_extra += ["--excluded-tests", pattern]
    if extra_tests:
        for pattern in extra_tests.split(","):
            p = pattern.strip()
            if p:
                gradle_extra += ["--tests", p]

    # Encode extra Gradle args as an env var so run-tests.sh can pick them up
    gradle_extra_str = " ".join(gradle_extra)

    cmd = [
        "docker", "run", "--rm",
        "--name", runner_name,
        "--network", network_name,
        "-v", f"{pgjdbc_dir}:/pgjdbc:rw",
        "-v", "pgjdbc-gradle-cache:/root/.gradle",
        "-e", "YB_HOST=yugabytedb",
        "-e", "YB_PORT=5433",
        "-e", f"SSL={ssl}",
        "-e", f"SCRAM={scram}",
        "-e", f"YB_ENABLE_YSQL_CONN_MGR={conn_mgr_env}",
        "-e", f"PGJDBC_GRADLE_EXTRA={gradle_extra_str}",
        "eclipse-temurin:17-jdk",
        "bash", "/pgjdbc/docker/yugabyte-server/scripts/run-tests.sh",
    ]

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    exit_code = 1

    def _stream() -> None:
        with open(log_file_path, "w", encoding="utf-8") as logf:
            assert proc.stdout is not None
            for line in proc.stdout:
                logf.write(line)
                logf.flush()
                print(line, end="")

    reader = threading.Thread(target=_stream, daemon=True)
    reader.start()

    try:
        if test_timeout > 0:
            proc.wait(timeout=test_timeout)
            exit_code = proc.returncode
        else:
            exit_code = proc.wait()
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        print()
        print(f"Tests timed out after {test_timeout}s.")
        print("Increase TEST_TIMEOUT_SEC or set it to 0 to disable the cap.")
        exit_code = -1
    except KeyboardInterrupt:
        proc.terminate()
        proc.wait()
        exit_code = -1

    reader.join(timeout=10)

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------
    try:
        captured = Path(log_file_path).read_text(encoding="utf-8")
    except OSError:
        captured = ""

    failures = extract_failures_from_output(captured)

    print()
    print("=" * 70)
    print(f"Full test log: {log_file_path}")
    print("=" * 70)

    if failures:
        print()
        print("FAILURE SUMMARY")
        print("-" * 70)
        for idx, (ident, msg) in enumerate(failures, 1):
            print(f"  {idx}. {ident}")
            if msg.strip():
                print(f"     {msg}")
        print()
        print("-" * 70)
        print(f"Total failures: {len(failures)}")

    print()

    # ------------------------------------------------------------------
    # [6/6] Cleanup
    # ------------------------------------------------------------------
    print("[6/6] Cleaning up containers, network, and Gradle cache volume...")
    _run_quiet(["docker", "rm", "-f", yb_container])
    _run_quiet(["docker", "network", "rm", network_name])
    _run_quiet(["docker", "volume", "rm", "pgjdbc-gradle-cache"])
    print("Done.")

    return exit_code if exit_code is not None else 0


# ---------------------------------------------------------------------------
# --exclude-until-pass mode: re-run the full suite, automatically adding the
# first failing test to EXCLUDED_TESTS after each failed run, until the run
# passes or nothing new can be excluded.
# ---------------------------------------------------------------------------

def _run_exclude_until_pass() -> int:
    script_path = Path(__file__).resolve()
    conn_mgr = os.environ.get("YB_ENABLE_YSQL_CONN_MGR", "no").lower() in ("1", "true", "yes")
    suffix = "_connmgr" if conn_mgr else "_noconnmgr"
    default_log = str(script_path.parent / f"pgjdbc_test_output{suffix}.log")
    log_file = os.environ.get("PGJDBC_TEST_OUTPUT_LOG", default_log)
    run_count = 0
    while True:
        run_count += 1
        print(f"\n>>> Run #{run_count} (excluded: {len(EXCLUDED_TESTS)}) <<<\n")
        result = subprocess.run(
            [sys.executable, str(script_path)],
            cwd=str(script_path.parent),
            env={**os.environ, "PGJDBC_STOP_ON_FIRST_FAILURE": "0"},
        )
        if result.returncode == 0:
            print(f"\nAll tests passed after {run_count} run(s).")
            return 0
        try:
            captured = Path(log_file).read_text(encoding="utf-8")
        except OSError:
            print("Could not read log file; stopping.")
            return result.returncode
        failures = extract_failures_from_output(captured)
        if not failures:
            print("No failures found in log; stopping.")
            return result.returncode
        ident = failures[0][0]
        pattern = extract_test_name_for_exclusion(ident)
        if not pattern:
            print(f"Could not derive exclusion pattern from: {ident!r}; stopping.")
            return result.returncode
        if not add_excluded_test(pattern):
            print(f"Pattern {pattern!r} already excluded or could not update file; stopping.")
            return result.returncode
        print(f"Excluded '{pattern}' — re-running...")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--exclude-until-pass":
        sys.exit(_run_exclude_until_pass())
    sys.exit(run_docker_tests())
