package com.ecommerce.product.service;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.exception.ApiException;
import com.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final IdempotencyGuard idempotencyGuard;

    public Page<Product> list(String category, String search, Pageable pageable) {
        // Listing endpoints are intentionally NOT cached (too many possible
        // filter/page combinations); only single-product lookups are cached
        // below, which is where the read traffic concentrates in practice.
        if (category != null && !category.isBlank()) {
            return productRepository.findByCategoryIgnoreCase(category, pageable);
        }
        if (search != null && !search.isBlank()) {
            return productRepository.findByNameContainingIgnoreCase(search, pageable);
        }
        return productRepository.findAll(pageable);
    }

    @Cacheable(cacheNames = "products", key = "#id")
    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    @Transactional
    public Product create(ProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stockQuantity(request.stockQuantity())
                .category(request.category())
                .imageUrl(request.imageUrl())
                .build();
        return productRepository.save(product);
    }

    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public Product update(Long id, ProductRequest request) {
        Product product = getById(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setCategory(request.category());
        product.setImageUrl(request.imageUrl());
        return productRepository.save(product);
    }

    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        productRepository.deleteById(id);
    }

    // idempotencyKey: caller-generated, held fixed across a Feign retry. Null/blank skips
    // idempotency protection (order-service always sends one).
    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public Product decrementStock(Long id, int quantity, String idempotencyKey) {
        if (hasKey(idempotencyKey) && !idempotencyGuard.claim("decrement-stock:" + id, idempotencyKey)) {
            log.info("Duplicate decrement-stock request for product {} (key {}) - returning current state", id, idempotencyKey);
            return getById(id);
        }
        try {
            Product product = getById(id);
            if (product.getStockQuantity() < quantity) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Insufficient stock for product " + id + ": requested " + quantity + ", available " + product.getStockQuantity());
            }
            product.setStockQuantity(product.getStockQuantity() - quantity);
            return productRepository.save(product);
        } catch (RuntimeException failure) {
            // Claim was premature - nothing was actually decremented, so a legitimate retry
            // must not be told "already done".
            if (hasKey(idempotencyKey)) {
                idempotencyGuard.release("decrement-stock:" + id, idempotencyKey);
            }
            throw failure;
        }
    }

    // Releases stock reserved via decrementStock() - declined payment or cancelled order.
    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public Product restock(Long id, int quantity, String idempotencyKey) {
        if (hasKey(idempotencyKey) && !idempotencyGuard.claim("restock:" + id, idempotencyKey)) {
            log.info("Duplicate restock request for product {} (key {}) - returning current state", id, idempotencyKey);
            return getById(id);
        }
        try {
            Product product = getById(id);
            product.setStockQuantity(product.getStockQuantity() + quantity);
            return productRepository.save(product);
        } catch (RuntimeException failure) {
            if (hasKey(idempotencyKey)) {
                idempotencyGuard.release("restock:" + id, idempotencyKey);
            }
            throw failure;
        }
    }

    private boolean hasKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
