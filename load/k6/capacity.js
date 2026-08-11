// k6 script for the T57 capacity gate (CAP-01..07, EDG-07).
//
// Drives POST /payment-simulations against a 2-instance payment-api fleet (round-robin, proving
// CAP-05's cross-instance coordination under load), classifies responses per design.md §6.2
// (200/202/422/429 expected, everything else a technical error), and logs a sampled subset of
// requestIds as JSON lines on stdout so a separate reconciliation pass (see
// load/capacity/reconcile.py) can poll them to a terminal state and prove CAP-02's
// zero-silent-loss claim without polling all ~150k requests inline.
//
//   docker run --rm --network payment-sandbox -v "$(pwd)/load:/load" grafana/k6:0.54.0 \
//     run -e BASE_URLS=http://payment-api-1:8080,http://payment-api-2:8080 \
//         -e RATE=167 -e DURATION=15m -e PRE_VUS=260 -e MAX_VUS=760 \
//         -e SCENARIO_LABEL=steady --summary-export=/load/reports/steady.summary.json \
//         /load/k6/capacity.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
const API_KEY = __ENV.API_KEY || 'dev-key-change-me';
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
};

export default function () {
  // __VU/__ITER are k6 built-ins (per-VU iteration counters) — each VU runs its own JS
  // instance, so a module-level counter would reset per VU instead of spreading across the
  // fleet. Combining VU + iteration distributes both the round-robin and the sample selection
  // evenly across the whole run instead of skewing every VU's first hit to the same instance.
  const cursor = __VU + __ITER;
  const idx = cursor % BASE_URLS.length;
  const baseUrl = BASE_URLS[idx];
  const idempotencyKey = uuidv4();

  const res = http.post(`${baseUrl}/payment-simulations`, PAYLOAD, {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
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
