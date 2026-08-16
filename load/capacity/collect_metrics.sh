#!/usr/bin/env bash
# Snapshots the CAP-04 resource signals (heap, GC, waiter pool, outbox backlog, DLQ, DB pool,
# Redis, Postgres) from the live fleet at a point in time and prints one JSON object on stdout.
# Sourced lib.sh must already be loaded by the caller (provides prometheus_metric/api_token/etc).
#
# Usage: collect_metrics.sh <label>
set -uo pipefail

LABEL="${1:-snapshot}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

API_TOKEN="$(api_token)"
SBUS_TOKEN="$(sbus_token)"

metric_or_null() {
  local v="$1"
  [ -z "$v" ] && echo null || echo "$v"
}

api1_prom="http://localhost:${API_HOST_PORT_1}/prometheus"
sbus1_prom="http://localhost:${SBUS_HOST_PORT_1}/prometheus"

api_pending=$(prometheus_metric "$api1_prom" "$API_TOKEN" "api_pending")
api_heap=$(prometheus_metric "$api1_prom" "$API_TOKEN" "jvm_memory_used_bytes" 'area="heap"')
api_gc_pause_count=$(prometheus_metric "$api1_prom" "$API_TOKEN" "jvm_gc_pause_seconds_count")
api_gc_pause_sum=$(prometheus_metric "$api1_prom" "$API_TOKEN" "jvm_gc_pause_seconds_sum")
api_completed=$(prometheus_metric "$api1_prom" "$API_TOKEN" "api_completed_total")
api_timeouts=$(prometheus_metric "$api1_prom" "$API_TOKEN" "api_timeouts_total")
api_failed=$(prometheus_metric "$api1_prom" "$API_TOKEN" "api_failed_total")

sbus_outbox_pending=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "sbus_outbox_pending")
sbus_dlq_unconfirmed=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "sbus_dlq_unconfirmed")
sbus_dlq_oldest_age=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "sbus_dlq_unconfirmed_oldest_age_seconds")
sbus_heap=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "jvm_memory_used_bytes" 'area="heap"')
sbus_gc_pause_count=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "jvm_gc_pause_seconds_count")
sbus_gc_pause_sum=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "jvm_gc_pause_seconds_sum")
sbus_hikari_active=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "hikaricp_connections_active")
sbus_hikari_pending=$(prometheus_metric "$sbus1_prom" "$SBUS_TOKEN" "hikaricp_connections_pending")

redis_used_memory=$(redis_info_field used_memory)
redis_connected_clients=$(redis_info_field connected_clients)

pg_active_conns=$(pg_stat "select count(*) from pg_stat_activity where datname='sbus';" | tr -d '[:space:]')
pg_outbox_rows=$(pg_stat "select count(*) from outbox_event;" | tr -d '[:space:]')

# Compacted to one line via `jq -c` — recovery.sh appends one snapshot per line to build a JSONL
# timeline, which a pretty-printed multi-line object here would break.
cat <<EOF | jq -c .
{"label":"${LABEL}","ts":"$(date -u +%Y-%m-%dT%H:%M:%SZ)",
"api_pending":$(metric_or_null "$api_pending"),
"api_heap_bytes":$(metric_or_null "$api_heap"),
"api_gc_pause_count":$(metric_or_null "$api_gc_pause_count"),
"api_gc_pause_seconds_sum":$(metric_or_null "$api_gc_pause_sum"),
"api_completed_total":$(metric_or_null "$api_completed"),
"api_timeouts_total":$(metric_or_null "$api_timeouts"),
"api_failed_total":$(metric_or_null "$api_failed"),
"sbus_outbox_pending":$(metric_or_null "$sbus_outbox_pending"),
"sbus_dlq_unconfirmed":$(metric_or_null "$sbus_dlq_unconfirmed"),
"sbus_dlq_oldest_age_seconds":$(metric_or_null "$sbus_dlq_oldest_age"),
"sbus_heap_bytes":$(metric_or_null "$sbus_heap"),
"sbus_gc_pause_count":$(metric_or_null "$sbus_gc_pause_count"),
"sbus_gc_pause_seconds_sum":$(metric_or_null "$sbus_gc_pause_sum"),
"sbus_hikari_active":$(metric_or_null "$sbus_hikari_active"),
"sbus_hikari_pending":$(metric_or_null "$sbus_hikari_pending"),
"redis_used_memory_bytes":$(metric_or_null "$redis_used_memory"),
"redis_connected_clients":$(metric_or_null "$redis_connected_clients"),
"postgres_active_connections":$(metric_or_null "$pg_active_conns"),
"postgres_outbox_rows":$(metric_or_null "$pg_outbox_rows")
}
EOF
