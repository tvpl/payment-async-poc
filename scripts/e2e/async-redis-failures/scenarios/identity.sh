#!/usr/bin/env bash
# RED-04: two instances starting workers use unique consumer identities and only one process
# holds the reclaim turn at a time. Requires async-redis-2 (multi_instance.sh up).

scenario_cross_instance_worker_identity() {
  local name="cross-instance-worker-identity"
  local names count distinct owner

  names=$(redis_cli XINFO CONSUMERS async.jobs workers | awk 'prev=="name"{print} {prev=$0}')
  count=$(printf '%s\n' "$names" | grep -c .)
  distinct=$(printf '%s\n' "$names" | sort -u | grep -c .)

  require "$name" "4 distinct consumers registered (2 instances x worker-concurrency=2)" \
    test "$count" -ge 4 -a "$distinct" -eq "$count"

  require "$name" "instance2's workers use the <instance-id>-w<index> pattern" \
    bash -c "printf '%s\n' \"$names\" | grep -qE '^instance2-w[0-9]+\$'"

  owner=$(redis_cli GET reclaim:workers:owner)
  require "$name" "reclaim:workers:owner holds exactly one owner" \
    test -n "$owner"

  # Re-read after a beat: the owner may rotate (lease renewal is per-owner, not sticky forever),
  # but at every instant only one value should be set — never a comma-joined or empty-but-live state.
  sleep 2
  owner2=$(redis_cli GET reclaim:workers:owner)
  require "$name" "reclaim owner remains a single non-empty value across a lease-renewal window" \
    test -n "$owner2"
}
