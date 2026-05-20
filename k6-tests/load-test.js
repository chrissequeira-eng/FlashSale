/**
 * k6 LOAD TEST - Auto Scaling Trigger Test
 *
 * Purpose: Gradually ramp up traffic until CPU > 60% triggers auto scaling.
 *
 * Run: k6 run load-test.js
 * With custom URL: k6 run -e BASE_URL=http://your-alb-dns.amazonaws.com load-test.js
 *
 * STAGES EXPLAINED:
 *
 *   0→50 users in 1min   → Warm up, CPU starts rising
 *   50 users for 3min    → Sustained load, CPU should hit 60%+
 *                          → WATCH CloudWatch: ASG should start scaling out here
 *   50→100 users in 1min → Push harder
 *   100 users for 3min   → Peak load, 2nd instance should be active
 *   100→0 in 2min        → Cool down
 *                          → WATCH CloudWatch: CPU drops, scale-in happens after cooldown
 *
 * WHAT TO WATCH IN AWS CONSOLE:
 *   1. EC2 > Auto Scaling Groups > Activity tab → see scale-out events
 *   2. CloudWatch > Alarms → see CPU alarm state change to ALARM
 *   3. EC2 > Load Balancers > Target Groups → see new instance become healthy
 *   4. k6 output → see instanceId rotating between instances
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successOrders = new Counter('successful_orders');
const failedOrders  = new Counter('failed_orders_out_of_stock');
const orderDuration = new Trend('order_duration_ms');

export const options = {
  stages: [
    { duration: '1m',  target: 50  },   // Ramp up to 50 users
    { duration: '3m',  target: 50  },   // Hold 50 users → trigger scale-out
    { duration: '1m',  target: 100 },   // Ramp up to 100 users
    { duration: '3m',  target: 100 },   // Hold 100 users → both instances active
    { duration: '2m',  target: 0   },   // Ramp down → trigger scale-in
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],    // Allow up to 5% failure (out-of-stock expected)
    http_req_duration: ['p(95)<5000'],   // 95th percentile under 5s
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Track which instances are serving traffic
const instanceTracker = {};

export default function () {
  const payload = JSON.stringify({
    productId: 1,
    quantity: 1,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/orders`, payload, params);
  const duration = Date.now() - start;
  orderDuration.add(duration);

  check(res, {
    'status 200': (r) => r.status === 200,
  });

  try {
    const body = JSON.parse(res.body);

    if (body.status === 'SUCCESS') {
      successOrders.add(1);
    } else {
      failedOrders.add(1);
    }

    // Track instance distribution - key observation for load balancing!
    if (body.instanceId) {
      instanceTracker[body.instanceId] = (instanceTracker[body.instanceId] || 0) + 1;
    }

  } catch (e) {
    // ignore
  }

  // No sleep = maximum pressure on the service
  // This is intentional to spike CPU faster
}

export function handleSummary(data) {
  // Print instance distribution at the end of the test
  console.log('\n=== INSTANCE DISTRIBUTION (Load Balancing) ===');
  for (const [instance, count] of Object.entries(instanceTracker)) {
    console.log(`  ${instance}: ${count} requests`);
  }
  console.log('=============================================\n');

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
