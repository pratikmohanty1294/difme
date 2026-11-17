// Baseline load test for the payment write path (ADR-014 k6). Run via ci/measure.sh.
// Open model (constant arrival rate): the request rate does not drop when the service slows, so latency and
// shedding are measured honestly (a closed model hides overload).
import http from 'k6/http';
import { check } from 'k6';
import { randomBytes } from 'k6/crypto';
import { Counter } from 'k6/metrics';

const TARGET = __ENV.TARGET || 'http://quarkus-cmd-svc.core-platform.svc:8080';
const RATE = parseInt(__ENV.RATE || '200');          // requests per second
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = parseInt(__ENV.ACCOUNTS || '1000'); // seeded by ci/measure.sh (ids 1..ACCOUNTS)

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', rate: Math.max(10, RATE / 10), timeUnit: '1s', duration: '15s',
              preAllocatedVUs: 20, maxVUs: 200, tags: { phase: 'warmup' } },
    steady: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION, startTime: '15s',
              preAllocatedVUs: 50, maxVUs: 1000, tags: { phase: 'steady' } },
  },
  thresholds: { 'http_req_duration{phase:steady}': ['p(99)<1000'] },
};

const outcomes = new Counter('payment_outcomes');

function hex(bytes) {
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');
}

// RFC 9562 UUIDv7: 48-bit ms timestamp, version 7, variant 10.
function uuidv7() {
  const ts = Date.now().toString(16).padStart(12, '0');
  const r = hex(randomBytes(10));
  const variant = ((parseInt(r[3], 16) & 0x3) | 0x8).toString(16);
  return `${ts.slice(0, 8)}-${ts.slice(8, 12)}-7${r.slice(0, 3)}-${variant}${r.slice(4, 7)}-${r.slice(7, 19)}`;
}

function account(n) {
  return `00000000-0000-0000-0000-${n.toString(16).padStart(12, '0')}`;
}

export default function () {
  const debit = 1 + Math.floor(Math.random() * ACCOUNTS);
  const credit = 1 + ((debit + Math.floor(Math.random() * (ACCOUNTS - 1))) % ACCOUNTS);
  const res = http.post(`${TARGET}/v1/payments`, JSON.stringify({
    debitAccountId: account(debit), creditAccountId: account(credit === debit ? (debit % ACCOUNTS) + 1 : credit),
    amount: { amountMinor: 100, currency: 'INR' }, channel: 'MOBILE', edgeFraudScore: 0.1,
  }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv7() } });

  let status = `http_${res.status}`;
  try { if (res.status < 300) status = JSON.parse(res.body).status || status; } catch (_) { /* keep http code */ }
  outcomes.add(1, { outcome: status });
  check(res, { 'no 5xx': (r) => r.status < 500 });
}
