package com.flashsale.product.service;

import com.flashsale.product.dto.ProductResponse;
import com.flashsale.product.dto.ReduceStockRequest;
import com.flashsale.product.entity.Product;
import com.flashsale.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ProductService - all business logic lives here, not in the controller.
 * The controller just handles HTTP; the service handles the work.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * Return all products.
     */
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Return one product by ID, or throw if not found.
     */
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        return toResponse(product);
    }

    /**
     * Reduce stock for a product.
     *
     * Uses @Transactional to ensure the DB update is atomic.
     * The repository query handles the "enough stock" check at the DB level.
     *
     * Returns true if stock was reduced successfully.
     * Returns false if out of stock (0 rows updated).
     */
    @Transactional
    public boolean reduceStock(Long productId, ReduceStockRequest request) {
        log.info("Attempting to reduce stock for product {} by {}", productId, request.getQuantity());

        int rowsUpdated = productRepository.reduceStock(productId, request.getQuantity());

        if (rowsUpdated == 0) {
            log.warn("Out of stock or product not found: productId={}", productId);
            return false;
        }

        log.info("Stock reduced successfully for product {}", productId);
        return true;
    }

    // Helper: convert entity → DTO
    private ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getStock());
    }
}
