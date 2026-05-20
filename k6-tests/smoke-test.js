/**
 * k6 SMOKE TEST - 10 users, 30 seconds
 *
 * Purpose: Verify everything works before real load testing.
 * Run: k6 run smoke-test.js
 *
 * What to observe:
 *   - All requests succeed (no errors)
 *   - Response times are reasonable
 *   - Stock reduces correctly
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Custom metrics ─────────────────────────────────────────────
const successOrders = new Counter('successful_orders');
const failedOrders  = new Counter('failed_orders');
const orderDuration = new Trend('order_duration_ms');

// ── Test configuration ─────────────────────────────────────────
export const options = {
  vus: 10,          // 10 virtual users (concurrent)
  duration: '30s',  // Run for 30 seconds
  thresholds: {
    http_req_failed: ['rate<0.1'],          // Less than 10% errors
    http_req_duration: ['p(95)<3000'],      // 95% of requests under 3s
  },
};

// ── Change this to your ALB DNS or localhost ───────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    productId: 1,     // PS5 Console (stock: 100)
    quantity: 1,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/orders`, payload, params);
  orderDuration.add(Date.now() - start);

  // Validate response
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has status field': (r) => JSON.parse(r.body).status !== undefined,
  });

  // Parse response body
  try {
    const body = JSON.parse(res.body);
    if (body.status === 'SUCCESS') {
      successOrders.add(1);
    } else {
      failedOrders.add(1);
    }

    // Show which instance handled this request
    console.log(`[${body.instanceId}] ${body.status} - product ${body.productId}`);
  } catch (e) {
    console.error('Failed to parse response:', res.body);
  }

  sleep(0.5);  // 500ms pause between requests per user
}
