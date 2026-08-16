package com.ecommerce.user.integration;

import com.ecommerce.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pilot for session 2's Testcontainers work: proves the mechanism (real Postgres, real HTTP
// layer, real Spring context) works end to end before this pattern gets copied to
// product-service, order-service, and cart-service.
//
// eureka.client.enabled=false: without this, the full app context tries to register with a
// Eureka server that doesn't exist in the test environment, causing slow connection-refused
// retries on every test run for no benefit - this test only cares about the HTTP+DB behavior.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    // Same image docker-compose.yml actually uses, so this test reflects the real deployment
    // target rather than whatever Testcontainers' own default Postgres version happens to be.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Test
    void register_thenLogin_fullRoundTrip() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterPayload("alice", "alice@example.com", "Password123!"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // The password actually made it through BCrypt, not stored as plaintext - real
        // end-to-end proof, not just "the endpoint returned 201".
        String storedPassword = userRepository.findByUsername("alice").orElseThrow().getPassword();
        assertThat(storedPassword).isNotEqualTo("Password123!");
        assertThat(storedPassword).startsWith("$2"); // BCrypt hash prefix

        String loginBody = objectMapper.writeValueAsString(new LoginPayload("alice", "Password123!"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterPayload("bob", "bob@example.com", "Password123!"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second registration with the same username - real uniqueness enforcement against
        // the actual database, not a mocked "assume it works" check.
        String duplicateBody = objectMapper.writeValueAsString(
                new RegisterPayload("bob", "different-email@example.com", "Password123!"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterPayload("carol", "carol@example.com", "Password123!"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String wrongLoginBody = objectMapper.writeValueAsString(new LoginPayload("carol", "WrongPassword123!"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongLoginBody))
                .andExpect(status().isUnauthorized());
    }

    // Plain local records instead of importing RegisterRequest/LoginRequest directly - keeps
    // this test decoupled from the exact production DTO shape, which matters less here than
    // proving the wire format works.
    private record RegisterPayload(String username, String email, String password) {
    }

    private record LoginPayload(String username, String password) {
    }
}
