package com.flashsale.order.service;

import com.flashsale.order.config.ProductServiceClient;
import com.flashsale.order.dto.OrderRequest;
import com.flashsale.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OrderService - processes incoming orders.
 *
 * Flow:
 *   1. Receive order request
 *   2. Simulate CPU work (so auto scaling triggers!)
 *   3. Call Product Service to reduce stock
 *   4. Return success or failure
 *
 * WHY ARTIFICIAL LOAD?
 * AWS Auto Scaling watches CPU usage. Without artificial work,
 * the service is so fast that CPU never spikes, so scaling never happens.
 * The simulated work makes this a realistic test for auto scaling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final ProductServiceClient productServiceClient;

    // Injected from application.yml - which instance is this?
    // On EC2, set this to the instance ID via environment variable
    @Value("${instance.id:local}")
    private String instanceId;

    /**
     * Process an order.
     *
     * Returns OrderResponse with status SUCCESS or FAILED.
     */
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("[{}] Processing order: productId={}, quantity={}",
                instanceId, request.getProductId(), request.getQuantity());

        // ── STEP 1: Simulate realistic CPU work ─────────────────────────
        // This is the KEY part for auto scaling experiments.
        // Without this, the service is too fast to trigger CPU alarms.
        simulateCpuWork();

        // ── STEP 2: Call Product Service to reduce stock ─────────────────
        boolean stockReduced = productServiceClient.reduceStock(
                request.getProductId(),
                request.getQuantity()
        );

        // ── STEP 3: Return result ────────────────────────────────────────
        if (stockReduced) {
            log.info("[{}] Order SUCCESS: productId={}", instanceId, request.getProductId());
            return new OrderResponse(
                    "SUCCESS",
                    "Order placed successfully",
                    request.getProductId(),
                    request.getQuantity(),
                    instanceId   // Shows WHICH instance handled this - great for observing load balancing
            );
        } else {
            log.warn("[{}] Order FAILED: out of stock for productId={}", instanceId, request.getProductId());
            return new OrderResponse(
                    "FAILED",
                    "Out of stock",
                    request.getProductId(),
                    request.getQuantity(),
                    instanceId
            );
        }
    }

    /**
     * Simulate CPU-intensive work to trigger auto scaling.
     *
     * We use a CPU-burning loop (more realistic than Thread.sleep).
     * Thread.sleep uses almost no CPU - it just pauses the thread.
     * A math loop actually uses CPU, which is what CloudWatch measures.
     *
     * Duration: ~200-300ms of real work per request.
     * Under load (500 concurrent users), this will push CPU above 60%.
     */
    private void simulateCpuWork() {
        // Run a tight math loop for ~200ms of CPU time
        long endTime = System.currentTimeMillis() + 200;
        double result = 0;
        while (System.currentTimeMillis() < endTime) {
            // Burn CPU with math operations
            result += Math.sqrt(Math.random()) * Math.log(Math.random() + 1);
        }
        // Use result to prevent JIT from optimizing away the loop
        log.debug("CPU work done, result={}", result);
    }
}
