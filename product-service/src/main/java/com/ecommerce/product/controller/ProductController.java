package com.ecommerce.product.controller;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** Public: GET /api/products?category=&search=&page=&size= */
    @GetMapping
    public Page<ProductResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        return productService.list(category, search, pageable).map(ProductResponse::from);
    }

    /** Public: GET /api/products/{id} - served from Redis cache when hot. */
    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return ProductResponse.from(productService.getById(id));
    }

    /** Protected (gateway requires JWT for non-GET /api/products/**). */
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        Product created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(created));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Internal only - called by order-service, not routed through the gateway.
    @PostMapping("/{id}/decrement-stock")
    public ProductResponse decrementStock(@PathVariable Long id, @RequestParam int quantity,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ProductResponse.from(productService.decrementStock(id, quantity, idempotencyKey));
    }

    // Internal only - called by order-service on a declined payment or order cancellation.
    @PostMapping("/{id}/restock")
    public ProductResponse restock(@PathVariable Long id, @RequestParam int quantity,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ProductResponse.from(productService.restock(id, quantity, idempotencyKey));
    }
}
