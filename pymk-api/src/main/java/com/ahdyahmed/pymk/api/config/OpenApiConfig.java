package com.ahdyahmed.pymk.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata shown at the head of Swagger UI
 * ({@code /swagger-ui.html}) and in the raw spec ({@code /v3/api-docs}).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pymkOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PYMK API")
                .version("v1")
                .description("People You May Know - portfolio-scale multi-stage "
                        + "recommendation backend (L0 candidate generation -> L1 -> L2 -> re-ranking).")
                .license(new License().name("MIT")));
    }
}
