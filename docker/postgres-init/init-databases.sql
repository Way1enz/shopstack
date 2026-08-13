-- Runs automatically on first Postgres container startup (mounted into
-- /docker-entrypoint-initdb.d/). Creates one database per service that
-- needs relational storage. cart-service is deliberately absent - it
-- uses Redis exclusively.
CREATE DATABASE user_db;
CREATE DATABASE product_db;
CREATE DATABASE order_db;

GRANT ALL PRIVILEGES ON DATABASE user_db TO ecommerce;
GRANT ALL PRIVILEGES ON DATABASE product_db TO ecommerce;
GRANT ALL PRIVILEGES ON DATABASE order_db TO ecommerce;
