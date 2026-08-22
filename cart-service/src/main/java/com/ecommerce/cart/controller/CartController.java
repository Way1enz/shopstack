package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.AddItemRequest;
import com.ecommerce.cart.dto.UpdateQuantityRequest;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.service.CartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// X-User-Id here comes from the gateway's AuthFilter, already validated. That's the Redis key.
@Tag(name = "Cart", description = "Shopping cart - requires a Bearer token")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public Cart getCart(@RequestHeader("X-User-Id") Long userId) {
        return cartService.getCart(userId);
    }

    @PostMapping("/items")
    public Cart addItem(@RequestHeader("X-User-Id") Long userId, @Valid @RequestBody AddItemRequest request) {
        return cartService.addItem(userId, request.productId(), request.quantity());
    }

    @PutMapping("/items/{productId}")
    public Cart updateQuantity(@RequestHeader("X-User-Id") Long userId,
                                @PathVariable Long productId,
                                @Valid @RequestBody UpdateQuantityRequest request) {
        return cartService.updateQuantity(userId, productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public Cart removeItem(@RequestHeader("X-User-Id") Long userId, @PathVariable Long productId) {
        return cartService.removeItem(userId, productId);
    }

    @DeleteMapping
    public void clearCart(@RequestHeader("X-User-Id") Long userId) {
        cartService.clearCart(userId);
    }
}
