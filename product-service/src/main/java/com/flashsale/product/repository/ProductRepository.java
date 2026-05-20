package com.flashsale.product.repository;

import com.flashsale.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * ProductRepository - Spring Data JPA handles all SQL for us.
 *
 * The custom query below uses a database-level atomic UPDATE
 * to prevent overselling during concurrent flash sale traffic.
 *
 * WHY "WHERE stock >= :quantity"?
 * Without this condition, two simultaneous requests could both
 * read stock=1, both decrement it, and end up with stock=-1 (oversold!).
 * The database enforces the check atomically.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Atomically reduce stock only if enough stock exists.
     * Returns number of rows updated (1 = success, 0 = out of stock).
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :quantity " +
           "WHERE p.id = :id AND p.stock >= :quantity")
    int reduceStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
