// Steady-state load for GC profiling: constant-arrival-rate against POST /payments,
// the busiest endpoint (every write the service handles goes through it, and it's
// the endpoint the planted audit-trail defect sits behind).
//
// Run against a JFR-recording instance, e.g.:
//   BASE_URL=http://localhost:8080 k6 run perf/steady-load.js
//
// Ten minutes at a constant rate so the JVM reaches steady state before either the
// baseline or the tuned recording is read — a cold run tells you nothing here.

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANTS = ['MR-4471', 'MR-1002', 'MR-2231', 'MR-9987', 'MR-5540'];

export const options = {
  scenarios: {
    steady_write_load: {
      executor: 'constant-arrival-rate',
      // Tune rate to whatever your machine can sustain without saturating CPU —
      // the point is a steady, repeatable arrival rate, not a peak number.
      // Start here; if p99 latency climbs unbounded during the run, lower it and
      // re-baseline rather than reading a saturated profile as a GC problem.
      rate: 50,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const merchantId = MERCHANTS[Math.floor(Math.random() * MERCHANTS.length)];
  const amountMinor = 1000 + Math.floor(Math.random() * 500000);

  const payload = JSON.stringify({
    merchantId,
    amountMinor,
    currency: 'GBP',
  });

  const res = http.post(`${BASE_URL}/payments`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'status is 201': (r) => r.status === 201,
  });
}
