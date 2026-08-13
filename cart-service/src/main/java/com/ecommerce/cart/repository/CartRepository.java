package com.ecommerce.cart.repository;

import com.ecommerce.cart.model.Cart;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

// No JPA/Postgres here - Redis is the whole database. Sliding TTL means abandoned carts clean
// themselves up automatically.
@Repository
@RequiredArgsConstructor
public class CartRepository {

    private final RedisTemplate<String, Cart> cartRedisTemplate;

    @Value("${cart.ttl-hours:72}")
    private long ttlHours;

    private String key(Long userId) {
        return "cart:" + userId;
    }

    public Optional<Cart> findByUserId(Long userId) {
        Cart cart = cartRedisTemplate.opsForValue().get(key(userId));
        return Optional.ofNullable(cart);
    }

    public Cart save(Cart cart) {
        cartRedisTemplate.opsForValue().set(key(cart.getUserId()), cart, Duration.ofHours(ttlHours));
        return cart;
    }

    public void deleteByUserId(Long userId) {
        cartRedisTemplate.delete(key(userId));
    }
}
