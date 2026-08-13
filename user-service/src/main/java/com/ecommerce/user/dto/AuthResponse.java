package com.ecommerce.user.dto;

public record AuthResponse(
        String token,
        String refreshToken,
        String tokenType,
        Long userId,
        String username
) {
    public static AuthResponse of(String token, String refreshToken, Long userId, String username) {
        return new AuthResponse(token, refreshToken, "Bearer", userId, username);
    }
}
