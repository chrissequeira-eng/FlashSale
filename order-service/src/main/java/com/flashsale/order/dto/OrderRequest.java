package com.flashsale.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OrderRequest - the payload the user sends to place an order.
 *
 * Example JSON:
 * {
 *   "productId": 1,
 *   "quantity": 1
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
    private Long productId;
    private Integer quantity;
}
