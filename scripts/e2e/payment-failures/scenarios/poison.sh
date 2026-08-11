#!/usr/bin/env bash
# PAY-07: a malformed/poison message never disappears silently — it lands in a recoverable
# DLQ state (DLQ_PENDING while unconfirmed, DLQ_PUBLISHED once the DLQ write itself is durable).

scenario_poison_message_to_dlq() {
  local name="poison-message-to-dlq"
  local poison_key="poison-${name}-$(date +%s)-$RANDOM"
  local before_count after_count

  before_count=$(psql_sandbox "SELECT count(*) FROM outbox_event WHERE topic = 'payment.simulation.dlq';" | tr -d '[:space:]')

  echo "${poison_key}:THIS_IS_NOT_VALID_AVRO_${RANDOM}" | docker exec -i payment-sandbox-kafka-1 \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic payment.simulation.requested \
    --property parse.key=true --property key.separator=: >/dev/null 2>&1

  local dlq_status=""
  for _ in $(seq 1 30); do
    sleep 2
    dlq_status=$(psql_sandbox "SELECT status FROM outbox_event WHERE topic = 'payment.simulation.dlq' ORDER BY id DESC LIMIT 1;" | tr -d '[:space:]')
    after_count=$(psql_sandbox "SELECT count(*) FROM outbox_event WHERE topic = 'payment.simulation.dlq';" | tr -d '[:space:]')
    [ "$after_count" -gt "$before_count" ] && [ "$dlq_status" = "DLQ_PUBLISHED" ] && break
  done

  if [ "$after_count" -le "$before_count" ]; then
    log_fail "${name}" "no new DLQ row appeared for the malformed message — it was silently dropped"
  elif [ "$dlq_status" != "DLQ_PUBLISHED" ]; then
    log_fail "${name}" "DLQ row never reached DLQ_PUBLISHED (last seen: ${dlq_status:-none}) — stuck or lost"
  else
    log_pass "${name}: malformed message routed to a new DLQ row, confirmed DLQ_PUBLISHED, no silent terminal loss"
  fi
}
