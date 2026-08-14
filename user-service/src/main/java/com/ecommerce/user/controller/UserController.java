package com.ecommerce.user.controller;

import com.ecommerce.user.dto.UserResponse;
import com.ecommerce.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Protected routes. The gateway has already validated the JWT for anything
 * under /api/users/** and forwards the caller's id as the X-User-Id header.
 */
@Tag(name = "Users", description = "User profile lookups")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponse me(@RequestHeader("X-User-Id") Long userId) {
        return UserResponse.from(userService.getById(userId));
    }

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable Long id) {
        return UserResponse.from(userService.getById(id));
    }
}
