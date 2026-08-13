package com.ecommerce.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Owns accounts: registration, login, password hashing, JWT issuance. Other services trust the
// X-User-Id header the gateway attaches rather than touching the users table directly.
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
