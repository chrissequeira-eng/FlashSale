package com.flashsale.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ProductResponse - what we send back to the caller.
 * Using a DTO (Data Transfer Object) keeps the API contract
 * separate from the database entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    private Long id;
    private String name;
    private Integer stock;
}
