package com.flashsale.product.controller;

import com.flashsale.product.dto.ProductResponse;
import com.flashsale.product.dto.ReduceStockRequest;
import com.flashsale.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ProductController - exposes all product-related REST endpoints.
 *
 * Endpoints:
 *   GET  /products          → list all products
 *   GET  /products/{id}     → get one product
 *   PUT  /products/{id}/reduce-stock → reduce stock (called by Order Service)
 */
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductService productService;

    // ── GET /products ───────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    // ── GET /products/{id} ──────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    // ── PUT /products/{id}/reduce-stock ─────────────────────────────────────
    // Called by Order Service to decrement stock.
    // Returns 200 + success message, or 409 Conflict if out of stock.
    @PutMapping("/{id}/reduce-stock")
    public ResponseEntity<Map<String, String>> reduceStock(
            @PathVariable Long id,
            @RequestBody ReduceStockRequest request) {

        boolean success = productService.reduceStock(id, request);

        if (success) {
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Stock reduced"));
        } else {
            // 409 Conflict = business rule violation (out of stock)
            return ResponseEntity.status(409)
                    .body(Map.of("status", "FAILED", "message", "Out of stock"));
        }
    }
}
