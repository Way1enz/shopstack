package com.ecommerce.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// Shopping cart. Redis is the primary datastore here, not a cache (key "cart:{userId}", TTL-based).
// Calls product-service via Feign to validate products and snapshot price/name when adding items.
@SpringBootApplication
@EnableFeignClients
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
