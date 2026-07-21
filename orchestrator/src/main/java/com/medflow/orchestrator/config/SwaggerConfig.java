package com.medflow.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI medFlowOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MedFlow Orchestrator API")
                        .description("Appointment lifecycle management for multi-tenant medical scheduling platform. " +
                                "All requests must include a valid JWT in the Authorization header — Kong Gateway " +
                                "validates the token and injects X-Tenant-ID automatically.")
                        .version("v0.1.0")
                        .contact(new Contact()
                                .name("Ronny Guilherme")
                                .email("ronny.guilherme@hotmail.com")
                                .url("https://github.com/RonnyGuilherme"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
