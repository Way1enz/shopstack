package com.ecommerce.product.service;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.exception.ApiException;
import com.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
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

    private final ProductRepository productRepository;

    public Page<Product> list(String category, String search, Pageable pageable) {
        // Listing isn't cached (too many filter/page combinations); only single-product lookups are.
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

    /** Used by order-service (via Feign) to confirm/decrement stock at checkout. */
    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public Product decrementStock(Long id, int quantity) {
        Product product = getById(id);
        if (product.getStockQuantity() < quantity) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Insufficient stock for product " + id + ": requested " + quantity + ", available " + product.getStockQuantity());
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        return productRepository.save(product);
    }

    // Releases stock reserved via decrementStock() - declined payment or order cancellation.
    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id")
    public Product restock(Long id, int quantity) {
        Product product = getById(id);
        product.setStockQuantity(product.getStockQuantity() + quantity);
        return productRepository.save(product);
    }
}
