package com.flashsale.product.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Product entity - maps to the "products" table in MySQL.
 * Keeping it intentionally simple: just id, name, and stock.
 */
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // How many units are available. Decremented on each successful order.
    @Column(nullable = false)
    private Integer stock;
}
