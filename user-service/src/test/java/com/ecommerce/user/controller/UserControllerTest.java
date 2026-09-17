package com.ecommerce.user.controller;

import com.ecommerce.user.entity.User;
import com.ecommerce.user.exception.ApiException;
import com.ecommerce.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// addFilters = false: auth is enforced at the gateway, not here.
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private User user(long id) {
        return User.builder()
                .id(id)
                .username("alice")
                .email("alice@example.com")
                .password("hashed")
                .role("ROLE_CUSTOMER")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void me_usesXUserIdHeader() throws Exception {
        when(userService.getById(7L)).thenReturn(user(7L));

        mockMvc.perform(get("/api/users/me").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("ROLE_CUSTOMER"));
    }

    @Test
    void me_missingHeader_returns400() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void me_userNotFound_returns404() throws Exception {
        when(userService.getById(99L)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "User not found: 99"));

        mockMvc.perform(get("/api/users/me").header("X-User-Id", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_returnsRequestedUser() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L));

        mockMvc.perform(get("/api/users/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(userService.getById(99L)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "User not found: 99"));

        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound());
    }
}
