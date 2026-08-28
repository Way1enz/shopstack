package com.ecommerce.product.service;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.exception.ApiException;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private IdempotencyGuard idempotencyGuard;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, idempotencyGuard);
    }

    private Product product(long id, int stock) {
        return Product.builder().id(id).name("Widget").price(BigDecimal.TEN).stockQuantity(stock).build();
    }

    // --- list ---

    @Test
    void list_byCategory_delegatesToCategoryQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = mock(Page.class);
        when(productRepository.findByCategoryIgnoreCase("tools", pageable)).thenReturn(page);

        Page<Product> result = productService.list("tools", null, pageable);

        assertThat(result).isSameAs(page);
        verify(productRepository, never()).findAll(pageable);
    }

    @Test
    void list_bySearch_delegatesToNameQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = mock(Page.class);
        when(productRepository.findByNameContainingIgnoreCase("wid", pageable)).thenReturn(page);

        Page<Product> result = productService.list(null, "wid", pageable);

        assertThat(result).isSameAs(page);
    }

    @Test
    void list_blankFilters_fallsBackToFindAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = mock(Page.class);
        when(productRepository.findAll(pageable)).thenReturn(page);

        Page<Product> result = productService.list("  ", " ", pageable);

        assertThat(result).isSameAs(page);
    }

    // --- getById ---

    @Test
    void getById_missing_throwsNotFound() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(1L))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    // --- create / update / delete ---

    @Test
    void create_savesBuiltProduct() {
        ProductRequest request = new ProductRequest("Widget", "desc", BigDecimal.ONE, 5, "tools", null);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.create(request);

        assertThat(result.getName()).isEqualTo("Widget");
        assertThat(result.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void update_existing_overwritesFields() {
        Product existing = product(1L, 5);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductRequest request = new ProductRequest("New", "desc", BigDecimal.TEN, 9, "cat", "img");

        Product result = productService.update(1L, request);

        assertThat(result.getName()).isEqualTo("New");
        assertThat(result.getStockQuantity()).isEqualTo(9);
    }

    @Test
    void delete_missing_throwsNotFound() {
        when(productRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> productService.delete(1L)).isInstanceOf(ApiException.class);
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void delete_existing_deletesById() {
        when(productRepository.existsById(1L)).thenReturn(true);

        productService.delete(1L);

        verify(productRepository).deleteById(1L);
    }

    // --- decrementStock ---

    @Test
    void decrementStock_noIdempotencyKey_skipsGuardAndDecrements() {
        Product existing = product(1L, 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.decrementStock(1L, 3, null);

        assertThat(result.getStockQuantity()).isEqualTo(7);
        verify(idempotencyGuard, never()).claim(anyString(), anyString());
    }

    @Test
    void decrementStock_firstAttemptWithKey_claimsAndDecrements() {
        when(idempotencyGuard.claim("decrement-stock:1", "key-1")).thenReturn(true);
        Product existing = product(1L, 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.decrementStock(1L, 3, "key-1");

        assertThat(result.getStockQuantity()).isEqualTo(7);
    }

    @Test
    void decrementStock_duplicateKey_returnsCurrentStateWithoutMutating() {
        when(idempotencyGuard.claim("decrement-stock:1", "key-1")).thenReturn(false);
        Product existing = product(1L, 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        Product result = productService.decrementStock(1L, 3, "key-1");

        assertThat(result.getStockQuantity()).isEqualTo(10);
        verify(productRepository, never()).save(any());
    }

    @Test
    void decrementStock_insufficientStock_throwsAndReleasesClaim() {
        when(idempotencyGuard.claim("decrement-stock:1", "key-1")).thenReturn(true);
        Product existing = product(1L, 2);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> productService.decrementStock(1L, 5, "key-1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        verify(idempotencyGuard).release("decrement-stock:1", "key-1");
        verify(productRepository, never()).save(any());
    }

    @Test
    void decrementStock_productMissing_releasesClaim() {
        when(idempotencyGuard.claim("decrement-stock:1", "key-1")).thenReturn(true);
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.decrementStock(1L, 5, "key-1"))
                .isInstanceOf(ApiException.class);

        verify(idempotencyGuard).release("decrement-stock:1", "key-1");
    }

    // --- restock ---

    @Test
    void restock_noKey_incrementsStock() {
        Product existing = product(1L, 5);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.restock(1L, 4, "");

        assertThat(result.getStockQuantity()).isEqualTo(9);
        verify(idempotencyGuard, never()).claim(anyString(), anyString());
    }

    @Test
    void restock_duplicateKey_returnsCurrentStateWithoutMutating() {
        when(idempotencyGuard.claim("restock:1", "key-2")).thenReturn(false);
        Product existing = product(1L, 5);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        Product result = productService.restock(1L, 4, "key-2");

        assertThat(result.getStockQuantity()).isEqualTo(5);
        verify(productRepository, never()).save(any());
    }

    @Test
    void restock_failureAfterClaim_releasesClaim() {
        when(idempotencyGuard.claim("restock:1", "key-2")).thenReturn(true);
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.restock(1L, 4, "key-2"))
                .isInstanceOf(ApiException.class);

        verify(idempotencyGuard).release("restock:1", "key-2");
    }
}
