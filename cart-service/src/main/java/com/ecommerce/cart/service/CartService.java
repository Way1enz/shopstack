package com.ecommerce.cart.service;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.client.ProductDTO;
import com.ecommerce.cart.exception.ApiException;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ProductClient productClient;

    public Cart getCart(Long userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> Cart.builder().userId(userId).updatedAt(Instant.now()).build());
    }

    public Cart addItem(Long userId, Long productId, int quantity) {
        ProductDTO product = productClient.getProduct(productId);
        if (product.stockQuantity() < quantity) {
            throw new ApiException(HttpStatus.CONFLICT, "Not enough stock for product " + productId);
        }

        Cart cart = getCart(userId);
        cart.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.setQuantity(existing.getQuantity() + quantity),
                        () -> cart.getItems().add(CartItem.builder()
                                .productId(product.id())
                                .productName(product.name())
                                .price(product.price())
                                .quantity(quantity)
                                .build())
                );
        cart.setUpdatedAt(Instant.now());
        return cartRepository.save(cart);
    }

    public Cart updateQuantity(Long userId, Long productId, int quantity) {
        Cart cart = getCart(userId);
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Item not in cart: " + productId));
        item.setQuantity(quantity);
        cart.setUpdatedAt(Instant.now());
        return cartRepository.save(cart);
    }

    public Cart removeItem(Long userId, Long productId) {
        Cart cart = getCart(userId);
        cart.getItems().removeIf(i -> i.getProductId().equals(productId));
        cart.setUpdatedAt(Instant.now());
        return cartRepository.save(cart);
    }

    public void clearCart(Long userId) {
        cartRepository.deleteByUserId(userId);
    }
}
