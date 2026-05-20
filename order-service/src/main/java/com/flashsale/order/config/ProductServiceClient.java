package com.flashsale.order.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ProductServiceClient - a simple HTTP client that calls Product Service.
 *
 * We use Spring's RestTemplate (simple, no extra dependencies).
 * The Product Service URL is injected from application.yml so we
 * can easily change it between local dev and AWS deployment.
 */
@Component
@Slf4j
public class ProductServiceClient {

    private final RestTemplate restTemplate;

    // This URL comes from application.yml (or environment variable in Docker/AWS)
    @Value("${product.service.url}")
    private String productServiceUrl;

    public ProductServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Call PUT /products/{id}/reduce-stock on the Product Service.
     *
     * Returns true if stock was reduced (HTTP 200).
     * Returns false if out of stock (HTTP 409) or any error.
     */
    public boolean reduceStock(Long productId, Integer quantity) {
        String url = productServiceUrl + "/products/" + productId + "/reduce-stock";

        // Build the request body: { "quantity": N }
        Map<String, Integer> body = Map.of("quantity", quantity);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    org.springframework.http.HttpMethod.PUT,
                    new org.springframework.http.HttpEntity<>(body),
                    Map.class
            );

            // HTTP 200 = success
            return response.getStatusCode().is2xxSuccessful();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // HTTP 409 = out of stock (expected business case)
            log.warn("Stock reduction failed for product {}: {}", productId, e.getMessage());
            return false;
        } catch (Exception e) {
            // Network error, Product Service down, etc.
            log.error("Error calling Product Service for product {}: {}", productId, e.getMessage());
            return false;
        }
    }
}
