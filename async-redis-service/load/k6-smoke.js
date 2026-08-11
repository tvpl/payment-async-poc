// k6 smoke test for async-redis-service.
//
//   k6 run load/k6-smoke.js
//   k6 run -e BASE_URL=http://localhost:8084 -e API_KEY=dev-key-change-me load/k6-smoke.js
//
// Submits a small, steady rate of jobs against POST /jobs, polls GET /jobs/{id} for the ones that
// come back 202, and asserts every job eventually completes. This is a functional smoke check (does
// accept -> process -> complete actually work end to end), not a capacity test - see
// docs/performance.md for the capacity disclaimer.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8084';
const API_KEY = __ENV.API_KEY || 'dev-key-change-me';
const RATE = parseInt(__ENV.RATE || '5');
const DURATION = __ENV.DURATION || '30s';

// 202 (still processing, poll for it) is an expected outcome, not a failure.
http.setResponseCallback(http.expectedStatuses(200, 202));

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const jobId = `smoke-${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    reference: jobId,
    amountCents: 1000,
    note: 'k6 smoke',
  });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
      'Idempotency-Key': jobId,
    },
  };

  const submit = http.post(`${BASE_URL}/jobs`, payload, params);
  const submitOk = check(submit, {
    'submit is 200 or 202': (r) => r.status === 200 || r.status === 202,
  });
  if (!submitOk) {
    return;
  }

  const body = submit.json();
  if (submit.status === 200) {
    check(body, {
      'completed result has the fee applied': (b) => b.result && b.result.feeCents === 20,
    });
    return;
  }

  // 202: poll the status URL until it completes, bounded so the smoke never hangs forever.
  for (let attempt = 0; attempt < 20; attempt++) {
    sleep(0.5);
    const poll = http.get(`${BASE_URL}${body.statusUrl}`, params);
    if (poll.status === 200) {
      const polled = poll.json();
      check(polled, {
        'polled result has the fee applied': (b) => b.result && b.result.feeCents === 20,
      });
      return;
    }
  }
  check(null, { 'job completed within the poll budget': () => false });
}
