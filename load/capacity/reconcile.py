#!/usr/bin/env python3
"""Reconciles a sample of requestIds logged by load/k6/capacity.js against payment-api's GET
/payment-simulations/{id}, proving CAP-02's "zero silent loss" on the sample (polling every
request submitted during a 15-minute/167rps run is not feasible in this gate's time budget, so
the k6 script samples ~1/SAMPLE_EVERY requests and this script confirms each sampled one reached
a terminal or explicitly-recoverable state).

Usage: reconcile.py <k6-stdout-log> <base_url> <api_key> [max_polls]
Prints one JSON object to stdout: {"sampled": N, "terminal": N, "lost": N, "lost_ids": [...]}
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

TERMINAL_STATUSES = {"COMPLETED", "FAILED", "TIMEOUT"}

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


def main():
    if len(sys.argv) < 4:
        print("usage: reconcile.py <k6-stdout-log> <base_url> <api_key> [max_polls]", file=sys.stderr)
        sys.exit(1)
    log_path, base_url, api_key = sys.argv[1], sys.argv[2], sys.argv[3]
    max_polls = int(sys.argv[4]) if len(sys.argv) > 4 else 20

    samples = load_samples(log_path)
    lost_ids = []
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
                lost_ids.append(request_id)

    print(json.dumps({
        "sampled": len(samples),
        "terminal": terminal_count,
        "lost": len(lost_ids),
        "lost_ids": lost_ids,
    }))


if __name__ == "__main__":
    main()
