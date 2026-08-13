package com.ecommerce.user.dto;

import com.ecommerce.user.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "may only contain letters, numbers, underscores, dots, and hyphens")
        String username,

        @NotBlank @Email String email,

        @NotBlank @StrongPassword String password
) {
}
