package com.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Resolved through Eureka by application name - no hardcoded host/port. */
@FeignClient(name = "product-service")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    ProductDTO getProduct(@PathVariable("id") Long id);

    /** Atomically decrements stock as part of checkout; internal service-to-service call. */
    @PostMapping("/api/products/{id}/decrement-stock")
    ProductDTO decrementStock(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);
}
