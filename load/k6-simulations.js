// k6 load test for the Payment Simulation API.
//
//   k6 run load/k6-simulations.js
//   k6 run -e BASE_URL=http://localhost:8080 -e RATE=200 -e DURATION=1m load/k6-simulations.js
//
// Drives a burst of POST /payment-simulations to exercise the async pipeline,
// virtual-thread waiting, the rate limiter (429s under heavy load) and the 200/202 mix.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '100');
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
  },
  thresholds: {
    // Most requests should resolve (200) or be accepted async (202); few server errors.
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
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, {
    'status is 200/202/422/429': (r) => [200, 202, 422, 429].includes(r.status),
  });
}
