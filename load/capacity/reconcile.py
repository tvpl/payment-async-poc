#!/usr/bin/env python3
"""Reconciles a sample of requestIds logged by load/k6/capacity.js against payment-api's GET
/payment-simulations/{id}, proving CAP-02's "zero silent loss" on the sample (polling every
request submitted during a 15-minute/167rps run is not feasible in this gate's time budget, so
the k6 script samples ~1/SAMPLE_EVERY requests and this script confirms each sampled one reached
a terminal or explicitly-recoverable state).

Usage: reconcile.py <k6-stdout-log> <base_url> <api_key> [max_polls]
Prints one JSON object to stdout: {"sampled": N, "terminal": N, "terminal_via_durable_fallback": N,
"lost": N, "lost_ids": [...]}

Why a durable-store fallback exists (2026-08-12): a 15-minute steady run reconciled immediately
after it ended reported 2/185 samples as "lost". Both had actually reached COMPLETED in
payment_sbus_message within ~1s of being created (confirmed by hand via `docker exec ... psql`).
Two things can make the HTTP-only check miss a request that genuinely finished:
  1. payment-api's Redis status-ttl (15m) can be shorter than or equal to the time between a
     sample being logged and reconciliation checking it, if the sample was near the start of a
     long scenario — the fast-path status entry has already expired.
  2. SbusStatusGateway's own circuit breaker (payment.sbus.failure-threshold / open-duration)
     is a best-effort fallback with its own failure budget; it can be open — skipping the call
     outright — precisely while the SBUS is under the same sustained load CAP-02 is testing.
Neither is a functional bug in request handling. Querying payment_sbus_message directly (the
durable store both paths ultimately serve from) sidesteps both: it depends on no TTL, no circuit
state, and no timing coincidence between config values. A request only counts as genuinely lost
if neither the HTTP path nor the durable store confirms it reached a terminal state.
"""
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
TERMINAL_STATUSES = {"COMPLETED", "FAILED", "TIMEOUT"}
# The SBUS side never writes TIMEOUT (that's an HTTP-wait-budget concept specific to payment-api);
# payment_sbus_message only ever holds PROCESSING/COMPLETED/FAILED (V7's CHECK constraint).
SBUS_TERMINAL_STATUSES = {"COMPLETED", "FAILED"}
PG_CONTAINER = "payment-sandbox-postgres-1"

# k6 wraps console.log() output in its own logfmt line, e.g.:
#   time="2026-08-11T09:57:53Z" level=info msg="{\"sample\":true,...}" source=console
# — not raw JSON — so the sample payload has to be pulled out of msg="..." and unescaped first.
MSG_RE = re.compile(r'msg="((?:[^"\\]|\\.)*)"')


def load_samples(log_path):
    samples = []
    with open(log_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            payload = line
            m = MSG_RE.search(line)
            if m:
                payload = m.group(1).replace('\\"', '"').replace("\\\\", "\\")
            if not payload.startswith("{"):
                continue
            try:
                obj = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if obj.get("sample") and obj.get("requestId"):
                samples.append(obj)
    return samples


def poll_terminal(base_url, api_key, request_id, max_polls):
    url = f"{base_url}/payment-simulations/{request_id}"
    for _ in range(max_polls):
        req = urllib.request.Request(url, headers={"X-API-Key": api_key})
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                body = json.loads(resp.read())
                status = body.get("status")
                if status in TERMINAL_STATUSES:
                    return status
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(1)
    return None


def durable_terminal_ids(request_ids):
    """Cross-checks request IDs still unresolved after HTTP polling against payment_sbus_message,
    the durable store both payment-api's Redis status and its SBUS fallback ultimately read from
    or feed off of. Returns the subset confirmed COMPLETED/FAILED there. Best-effort: if the
    Postgres container is unreachable (e.g. this gate ever runs without it), returns an empty set
    rather than failing the whole reconciliation on an unrelated infrastructure gap.
    """
    # requestIds are UUIDs payment-api mints internally, but they arrive here via a k6 log —
    # validate the shape before interpolating rather than trusting the source transitively.
    uuid_ids = [rid for rid in request_ids if UUID_RE.fullmatch(rid)]
    if not uuid_ids:
        return set()
    ids_literal = ",".join(f"'{rid}'" for rid in uuid_ids)
    query = (
        f"SELECT request_id FROM payment_sbus_message "
        f"WHERE request_id IN ({ids_literal}) AND status IN ('COMPLETED', 'FAILED');"
    )
    try:
        result = subprocess.run(
            ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", "sbus", "-d", "sbus", "-tA", "-c", query],
            capture_output=True, text=True, timeout=15, check=True,
        )
    except (subprocess.SubprocessError, OSError):
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def main():
    if len(sys.argv) < 4:
        print("usage: reconcile.py <k6-stdout-log> <base_url> <api_key> [max_polls]", file=sys.stderr)
        sys.exit(1)
    log_path, base_url, api_key = sys.argv[1], sys.argv[2], sys.argv[3]
    max_polls = int(sys.argv[4]) if len(sys.argv) > 4 else 20

    samples = load_samples(log_path)
    pending_ids = []
    terminal_count = 0
    # Polled concurrently — sequential polling made total wall-clock time samples x max_polls,
    # which hung for hours against constrained-core's deliberately huge backlog (design.md §6.1
    # itself expects ~105,000 queued items there; that scenario's samples are NOT expected to
    # resolve quickly, and a sequential loop paid max_polls seconds for every single one of them
    # instead of paying it once for the whole batch). Concurrency bounds wall time to ~max_polls
    # seconds total, independent of how many samples were logged.
    with ThreadPoolExecutor(max_workers=32) as pool:
        results = pool.map(lambda s: (s["requestId"], poll_terminal(base_url, api_key, s["requestId"], max_polls)), samples)
        for request_id, status in results:
            if status:
                terminal_count += 1
            else:
                pending_ids.append(request_id)

    confirmed_durable = durable_terminal_ids(pending_ids)
    lost_ids = [rid for rid in pending_ids if rid not in confirmed_durable]

    print(json.dumps({
        "sampled": len(samples),
        "terminal": terminal_count + len(confirmed_durable),
        "terminal_via_durable_fallback": len(confirmed_durable),
        "lost": len(lost_ids),
        "lost_ids": lost_ids,
    }))


if __name__ == "__main__":
    main()
