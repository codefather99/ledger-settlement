import http from 'k6/http';
import { check } from 'k6';

// SJV-L1 load profile: 200 concurrent users for 60 seconds against the main read endpoint.
// Override for smoke tests only: k6 run -e VUS=10 -e DURATION=10s perf/threads-load.js
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANT = __ENV.MERCHANT_ID || 'MR-4471';

export const options = {
  scenarios: {
    settlement_read: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 200),
      duration: __ENV.DURATION || '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/payments/settlement?merchantId=${MERCHANT}`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}