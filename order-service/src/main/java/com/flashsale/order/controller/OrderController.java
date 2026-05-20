package com.flashsale.order.controller;

import com.flashsale.order.dto.OrderRequest;
import com.flashsale.order.dto.OrderResponse;
import com.flashsale.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * OrderController - exposes the order placement endpoint.
 *
 * POST /orders  →  place a new order
 *
 * This is the endpoint that gets hammered during load tests.
 * The ALB routes traffic across multiple Order Service instances.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /orders
     *
     * Request body:
     * {
     *   "productId": 1,
     *   "quantity": 1
     * }
     *
     * Response:
     * {
     *   "status": "SUCCESS",
     *   "message": "Order placed successfully",
     *   "productId": 1,
     *   "quantity": 1,
     *   "instanceId": "i-0abc123..."   ← which EC2 handled this request
     * }
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.placeOrder(request);

        // Return 200 for both SUCCESS and FAILED (FAILED = business failure, not server error)
        return ResponseEntity.ok(response);
    }

    // ── Health check shortcut ────────────────────────────────────────────────
    // You can also use /actuator/health, but this is a quick manual check
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Order Service is UP");
    }
}
