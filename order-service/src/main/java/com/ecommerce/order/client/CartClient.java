package com.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

// This bypasses the gateway (internal service-to-service call), so order-service sets X-User-Id
// itself instead of relying on AuthFilter to inject it.
@FeignClient(name = "cart-service")
public interface CartClient {

    @GetMapping("/api/cart")
    CartDTO getCart(@RequestHeader("X-User-Id") Long userId);

    @DeleteMapping("/api/cart")
    void clearCart(@RequestHeader("X-User-Id") Long userId);
}
