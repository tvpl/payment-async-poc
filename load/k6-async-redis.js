// k6 benchmark for the Kafka-free async->sync service (Redis Streams + BRPOP).
//
//   k6 run -e BASE_URL=http://localhost:8084 -e RATE=200 -e DURATION=1m load/k6-async-redis.js
//
// Each iteration POSTs a job; the API enqueues on a Redis Stream and blocks (virtual thread) on
// BRPOP until the worker releases the result — so a 200 means the whole async->sync round trip
// completed within the wait timeout. Reports end-to-end latency (sync_ms) and the 200/202 mix, the
// same shape as the Kafka path's k6 so the two can be compared apples-to-apples.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8084';
const RATE = parseInt(__ENV.RATE || '200');
const DURATION = __ENV.DURATION || '1m';

const syncLatency = new Trend('sync_ms', true);
const statusMix = new Counter('job_status_mix');

http.setResponseCallback(http.expectedStatuses(200, 202));

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 4),
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.05'],
    'checks': ['rate>0.95'],
  },
};

const headers = { 'Content-Type': 'application/json' };

export default function () {
  const payload = JSON.stringify({
    reference: `ORDER-${Math.floor(Math.random() * 1e9)}`,
    amountCents: 12550,
    note: 'k6',
  });
  const start = Date.now();
  const res = http.post(`${BASE_URL}/jobs`, payload, { headers, tags: { name: 'submit' } });
  syncLatency.add(Date.now() - start);
  statusMix.add(1, { status: String(res.status) });
  check(res, {
    'submitted (200/202)': (r) => r.status === 200 || r.status === 202,
    'completed synchronously (200)': (r) => r.status === 200,
  });
}
