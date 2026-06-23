// k6 load test for the Payment Simulation API.
//
//   k6 run load/k6-simulations.js
//   k6 run -e BASE_URL=http://localhost:8080 -e RATE=200 -e DURATION=1m load/k6-simulations.js
//   k6 run -e API_KEY=dev-key-change-me load/k6-simulations.js          # auth is ON by default
//   k6 run -e EXECUTOR=ramp -e RATE=400 load/k6-simulations.js          # ramping arrival rate
//   k6 run -o experimental-prometheus-rw load/k6-simulations.js         # push metrics to Prometheus
//
// Drives a burst of POST /payment-simulations to exercise the async pipeline,
// virtual-thread waiting, the rate limiter (429s under heavy load) and the 200/202 mix.
//
// Auth: the API ships with X-API-Key auth ENABLED, so every request sends the key.
// Override with -e API_KEY=... (must match PAYMENT_API_KEY on the api-service).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-key-change-me';
const RATE = parseInt(__ENV.RATE || '100');
const DURATION = __ENV.DURATION || '1m';
// EXECUTOR: "constant" (steady RATE) or "ramp" (climb 0 -> RATE -> 0 to see backpressure).
const EXECUTOR = __ENV.EXECUTOR || 'constant';

// Per-status counter so the 200/202/422/429 mix is visible in the summary and in Grafana.
const statusMix = new Counter('sim_status_mix');

// 429 (rate limit) and 422 (declined by Core) are EXPECTED outcomes under load, not
// failures. Mark them as expected so http_req_failed only counts real errors (401/5xx).
http.setResponseCallback(http.expectedStatuses(200, 202, 422, 429));

const scenario = EXECUTOR === 'ramp'
  ? {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
      stages: [
        { target: RATE, duration: DURATION },      // climb to RATE
        { target: RATE, duration: DURATION },      // hold
        { target: 0, duration: DURATION },         // ramp down
      ],
    }
  : {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 1000,
    };

export const options = {
  scenarios: { sim: scenario },
  thresholds: {
    // Only unexpected statuses (e.g. 401 missing key, 5xx) count as failures here.
    'http_req_failed': ['rate<0.05'],
  },
};

const payload = JSON.stringify({
  merchantId: 'MERCHANT-001',
  amount: 125.5,
  currency: 'BRL',
  paymentMethod: 'CREDIT_CARD',
  brand: 'VISA',
  installments: 3,
  captureMode: 'AUTHORIZE_AND_CAPTURE',
});

export default function () {
  const res = http.post(`${BASE_URL}/payment-simulations`, payload, {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
    tags: { name: 'create' },
  });
  statusMix.add(1, { status: String(res.status) });
  check(res, {
    'status is 200/202/422/429': (r) => [200, 202, 422, 429].includes(r.status),
    'not unauthorized (401)': (r) => r.status !== 401,
  });
}
