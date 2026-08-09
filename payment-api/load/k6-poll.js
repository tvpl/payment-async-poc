// k6 load test exercising the ASYNC (202 -> poll) path of the Payment Simulation API.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e VUS=50 -e DURATION=1m load/k6-poll.js
//
// Each iteration POSTs a simulation and, if it is not terminal yet (202), polls
// GET /payment-simulations/{requestId} until COMPLETED/FAILED/TIMEOUT. This drives
// the durable status path (Redis + SBUS fallback) rather than the synchronous wait.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-key-change-me';
const VUS = parseInt(__ENV.VUS || '50');
const DURATION = __ENV.DURATION || '1m';
const MAX_POLLS = parseInt(__ENV.MAX_POLLS || '20');

const resolveLatency = new Trend('sim_resolve_ms', true);

http.setResponseCallback(http.expectedStatuses(200, 202, 404, 422, 429));

export const options = {
  scenarios: {
    poll: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.05'],
    'checks': ['rate>0.95'],
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

const TERMINAL = ['COMPLETED', 'FAILED', 'TIMEOUT'];
const headers = { 'Content-Type': 'application/json', 'X-API-Key': API_KEY };

export default function () {
  const start = Date.now();
  const res = http.post(`${BASE_URL}/payment-simulations`, payload, { headers, tags: { name: 'create' } });
  check(res, { 'created (200/202/422/429)': (r) => [200, 202, 422, 429].includes(r.status) });

  let body;
  try { body = res.json(); } catch (_) { return; }
  if (!body || !body.requestId) return;

  let status = body.status;
  if (res.status === 429) return; // throttled — nothing to poll

  for (let i = 0; i < MAX_POLLS && !TERMINAL.includes(status); i++) {
    sleep(0.5);
    const g = http.get(`${BASE_URL}/payment-simulations/${body.requestId}`, { headers, tags: { name: 'status' } });
    if (g.status === 200) {
      try { status = g.json('status'); } catch (_) { /* keep polling */ }
    }
  }

  resolveLatency.add(Date.now() - start);
  check(status, { 'reached terminal state': (s) => TERMINAL.includes(s) });
}
