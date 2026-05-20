package com.flashsale.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReduceStockRequest - payload sent by Order Service when reducing stock.
 * Example: { "quantity": 1 }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReduceStockRequest {
    private Integer quantity;
}
