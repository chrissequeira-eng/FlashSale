/**
 * k6 SPIKE TEST - Sudden Traffic Burst (Flash Sale Simulation)
 *
 * Purpose: Simulate a real flash sale scenario where traffic goes from
 *          0 to 500 users instantly (like a product drops at 12:00 PM).
 *
 * Run: k6 run spike-test.js -e BASE_URL=http://your-alb-dns.amazonaws.com
 *
 * STAGES:
 *   0→500 users in 10s   → SUDDEN SPIKE (flash sale starts!)
 *   500 users for 2min   → Flash sale in progress
 *   500→0 in 10s         → Flash sale ends
 *
 * KEY THINGS TO OBSERVE:
 *   1. Initial latency spike as the single instance gets overwhelmed
 *   2. ASG detects high CPU → scales out (takes ~2-3 minutes)
 *   3. After new instance is healthy, latency improves
 *   4. Many "Out of stock" responses as 100 units sell out fast
 *   5. CloudWatch alarm: CPUUtilization goes ALARM → OK
 *
 * IMPORTANT NOTE:
 *   Auto Scaling has a startup delay (~2-3 min for instance + Spring Boot).
 *   The spike may be over before the 2nd instance kicks in.
 *   This is NORMAL and teaches you about scaling lag in real systems.
 *   Solution: pre-warm (set desired=2 before flash sale starts).
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const successOrders = new Counter('successful_orders');
const outOfStockErrors = new Counter('out_of_stock');
const serverErrors = new Counter('server_errors');

export const options = {
  stages: [
    { duration: '10s', target: 500 },  // BOOM - spike to 500 users
    { duration: '2m',  target: 500 },  // Sustain the spike
    { duration: '10s', target: 0   },  // Drop off
  ],
  thresholds: {
    // During a spike, we accept higher error rates (stock runs out)
    http_req_failed: ['rate<0.5'],      // Allow up to 50% failure (out-of-stock)
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Randomize productId to test different products
const PRODUCT_IDS = [1, 2, 3];

export default function () {
  const productId = PRODUCT_IDS[Math.floor(Math.random() * PRODUCT_IDS.length)];

  const payload = JSON.stringify({
    productId: productId,
    quantity: 1,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',  // Don't wait forever if service is overloaded
  };

  const res = http.post(`${BASE_URL}/orders`, payload, params);

  check(res, {
    'not a server error': (r) => r.status < 500,
  });

  if (res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      if (body.status === 'SUCCESS') {
        successOrders.add(1);
      } else {
        outOfStockErrors.add(1);
      }
    } catch(e) {}
  } else {
    serverErrors.add(1);
    console.error(`Server error: HTTP ${res.status}`);
  }

  // No sleep - max pressure
}
