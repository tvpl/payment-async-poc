// k6 script for the capacity gate (CAP-01..07, EDG-07, AUD-30/AD-007).
//
// Drives POST /payment-simulations against a 2-instance payment-api fleet (round-robin, proving
// CAP-05's cross-instance coordination under load), classifies responses per design.md §6.2
// (200/202/422/429 expected, everything else a technical error), and logs a sampled subset of
// requestIds as JSON lines on stdout so a separate reconciliation pass (see
// load/capacity/reconcile.py) can poll them to a terminal state and prove CAP-02's
// zero-silent-loss claim without polling all ~150k requests inline.
//
// AUD-30: load is distributed across >=2 tenant API keys (round-robin, same pattern as the
// instance distribution below) so this measures the route's real capacity instead of a single
// tenant's rate-limit ceiling — the exact gap that made the 2026-08-12 report certify the
// tenant bucket (50/s, 70% 429) as if it were system capacity.
//
//   docker run --rm --network payment-sandbox -v "$(pwd)/load:/load" grafana/k6:0.54.0 \
//     run -e BASE_URLS=http://payment-api-1:8080,http://payment-api-2:8080 \
//         -e API_KEYS=key-a,key-b \
//         -e RATE=17 -e DURATION=15m -e PRE_VUS=100 -e MAX_VUS=300 \
//         -e SCENARIO_LABEL=steady --summary-export=/load/reports/steady.summary.json \
//         /load/k6/capacity.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
// AUD-30: API_KEYS (comma-separated) replaces the single-tenant API_KEY. API_KEY is still read as
// a one-key fallback so ad hoc single-tenant runs (e.g. the CAP-05 duplicate-key probe scripts)
// keep working unchanged.
const API_KEYS = (__ENV.API_KEYS || __ENV.API_KEY || 'dev-key-change-me').split(',');
const RATE = parseInt(__ENV.RATE || '10');
const DURATION = __ENV.DURATION || '30s';
const PRE_VUS = parseInt(__ENV.PRE_VUS || '50');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '200');
const SCENARIO_LABEL = __ENV.SCENARIO_LABEL || 'unnamed';
// One sampled requestId logged per SAMPLE_EVERY iterations, for the reconciliation pass.
const SAMPLE_EVERY = parseInt(__ENV.SAMPLE_EVERY || '50');

// One Counter per status class (rather than a single tagged Counter) so each total lands as its
// own line in `k6 run --summary-export` JSON — the summary export does not break tagged Counters
// down by tag, only totals per metric name, and load/capacity/generate_report.py reads these
// directly instead of parsing k6's raw per-datapoint output stream.
const status200 = new Counter('cap_status_200');
const status202 = new Counter('cap_status_202');
const status422 = new Counter('cap_status_422');
const status429 = new Counter('cap_status_429');
const technicalErrors = new Counter('cap_technical_errors');
// Per-tenant request counts, so a report/investigation can confirm the split across API_KEYS is
// actually even rather than assuming it from the average rate alone.
const keyCounters = API_KEYS.map((_, i) => new Counter(`cap_key_${i}_reqs`));

// Expected outcomes under load per design.md §6.2: 200 (completed within wait-timeout), 202
// (fell back before terminal), 422 (Core decline — CORE_DECLINE_PCT), 429 (rate limiter). Only
// 401/5xx/network failures count as the "technical error rate <0.1%" CAP-02 budgets.
http.setResponseCallback(http.expectedStatuses(200, 202, 422, 429));

const PAYLOAD = JSON.stringify({
  merchantId: 'MERCHANT-001',
  amount: 125.50,
  currency: 'BRL',
  paymentMethod: 'CREDIT_CARD',
  brand: 'VISA',
  installments: 3,
  captureMode: 'AUTHORIZE_AND_CAPTURE',
});

export const options = {
  scenarios: {
    [SCENARIO_LABEL]: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // AD-007 / spec.md P3 AC4: latency targets asserted as k6 thresholds, not just reported.
  thresholds: {
    http_req_duration: ['avg<300', 'p(99)<10000'],
  },
};

export default function () {
  // __VU/__ITER are k6 built-ins (per-VU iteration counters) — each VU runs its own JS
  // instance, so a module-level counter would reset per VU instead of spreading across the
  // fleet. Combining VU + iteration distributes both the round-robin and the sample selection
  // evenly across the whole run instead of skewing every VU's first hit to the same instance.
  const cursor = __VU + __ITER;
  const idx = cursor % BASE_URLS.length;
  const baseUrl = BASE_URLS[idx];
  // AUD-30: tenant key picked from exec.scenario.iterationInInstance — a counter incremented
  // once per iteration *globally across every VU*, in actual chronological request order — not
  // (VU+ITER), and not Math.random() either. RedisRateLimiter.java enforces the tenant budget in
  // a *per-second* fixed window, and k6's constant-arrival-rate executor schedules iterations
  // deterministically to hit the target rate: a (VU+ITER) modulus correlates with which second a
  // request lands in (whole seconds clustered onto one tenant even though the run-wide split
  // looked even — measured 16.5% 429 on a live run), and a per-iteration coin flip still leaves
  // enough binomial variance per second at ~8.5 offered against a 10/s cap to occasionally miss
  // the AUD-30 1% budget (measured 3.25% on a live run). Alternating on the true global
  // chronological sequence keeps every single 1-second window within 1 of an even split.
  const keyIdx = exec.scenario.iterationInInstance % API_KEYS.length;
  const apiKey = API_KEYS[keyIdx];
  keyCounters[keyIdx].add(1);
  const idempotencyKey = uuidv4();

  const res = http.post(`${baseUrl}/payment-simulations`, PAYLOAD, {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey,
      'Idempotency-Key': idempotencyKey,
    },
    tags: { instance: baseUrl },
  });

  const isExpected = [200, 202, 422, 429].includes(res.status);
  switch (res.status) {
    case 200: status200.add(1); break;
    case 202: status202.add(1); break;
    case 422: status422.add(1); break;
    case 429: status429.add(1); break;
    default: technicalErrors.add(1); break;
  }

  if (cursor % SAMPLE_EVERY === 0) {
    let requestId = null;
    if (isExpected && res.status !== 429) {
      try {
        requestId = JSON.parse(res.body).requestId || null;
      } catch (e) {
        requestId = null;
      }
    }
    console.log(JSON.stringify({
      sample: true,
      scenario: SCENARIO_LABEL,
      instance: baseUrl,
      status: res.status,
      requestId,
      idempotencyKey,
    }));
  }
}
