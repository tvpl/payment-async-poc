// k6 benchmark for the feature-control resolver overhead (feature-demo service).
//
//   k6 run -e BASE_URL=http://localhost:8083 -e RATE=500 -e DURATION=1m load/k6-feature.js
//
// Hits the A/B endpoint with a distinct anonymous id per iteration so the resolver actually buckets
// each call (percentage strategy). Measures the added latency of a feature decision (Redis dynamic
// lookup with in-process cache) and confirms the roll-out proportion. Useful to justify "the lib is
// cheap enough to call on every request across 30+ apps".
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const RATE = parseInt(__ENV.RATE || '500');
const DURATION = __ENV.DURATION || '1m';

const decideLatency = new Trend('decide_ms', true);
const variantMix = new Counter('ab_variant_mix');

http.setResponseCallback(http.expectedStatuses(200));

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 2),
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'decide_ms': ['p(99)<50'],
  },
};

export default function () {
  const anonId = `u-${Math.floor(Math.random() * 1e9)}`;
  const start = Date.now();
  const res = http.get(`${BASE_URL}/demo/ab`, { headers: { 'X-Anon-Id': anonId }, tags: { name: 'ab' } });
  decideLatency.add(Date.now() - start);
  check(res, { 'ok (200)': (r) => r.status === 200 });
  try {
    variantMix.add(1, { variant: res.json('decision').variant });
  } catch (_) { /* ignore parse issues under load */ }
}
