package com.flashsale.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OrderResponse - what we send back after processing an order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private String status;       // "SUCCESS" or "FAILED"
    private String message;      // Human-readable explanation
    private Long productId;
    private Integer quantity;
    private String instanceId;   // Which EC2 instance handled this request (useful for observing load balancing)
}
