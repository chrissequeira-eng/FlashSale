-- Seed data: only inserts if products table is empty.
-- This gives us test products for the flash sale simulation.

INSERT INTO products (name, stock)
SELECT 'PS5 Console', 100
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'PS5 Console');

INSERT INTO products (name, stock)
SELECT 'Xbox Series X', 50
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Xbox Series X');

INSERT INTO products (name, stock)
SELECT 'Nintendo Switch OLED', 200
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Nintendo Switch OLED');
