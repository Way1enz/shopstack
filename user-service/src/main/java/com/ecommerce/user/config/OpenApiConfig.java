package com.ecommerce.user.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Server points at the gateway; this service isn't port-mapped to the host on its own.
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopStack - User Service")
                        .description("Registration, login, and profile lookups. "
                                + "/api/auth/** is public; /api/users/** requires a Bearer token.")
                        .version("v1"))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Via API Gateway (recommended)")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
