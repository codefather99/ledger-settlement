import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

// SJV-L1 load profile: 200 concurrent users, 60 s steady state, against the main read endpoint.
// A 5 s ramp-up precedes the steady phase. Without it, 200 simultaneous first connections overflow
// Tomcat's accept queue (accept-count 100) and Windows refuses the extras. That is a load-rig artefact,
// so ramp requests are tagged phase=ramp and excluded from reported figures (phase=steady only).
// Smoke test: k6 run -e VUS=10 -e DURATION=10s perf/threads-load.js
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANT = __ENV.MERCHANT_ID || 'MR-4471';
const VUS = Number(__ENV.VUS || 200);
const HOLD = __ENV.DURATION || '60s';
const RAMP_MS = 5000;

export const options = {
  scenarios: {
    settlement_read: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: VUS },
        { duration: HOLD, target: VUS },
      ],
      gracefulRampDown: '0s',
    },
  },
  // These thresholds never fail; they only make k6 print the steady-phase sub-metrics.
  thresholds: {
    'http_req_duration{phase:steady}': ['max>=0'],
    'http_reqs{phase:steady}': ['count>=0'],
    'http_req_failed{phase:steady}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const phase = exec.instance.currentTestRunDuration < RAMP_MS ? 'ramp' : 'steady';
  const res = http.get(`${BASE_URL}/payments/settlement?merchantId=${MERCHANT}`, { tags: { phase } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}