package com.medflow.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AvailabilityClientConfig {

    @Value("${availability.engine.url}")
    private String availabilityEngineUrl;

    @Value("${availability.engine.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${availability.engine.read-timeout-ms:5000}")
    private int readTimeoutMs;

    /**
     * RestClient targeting the Availability Engine (Go service).
     * Uses Spring 6.1+ RestClient (modern replacement for RestTemplate).
     */
    @Bean
    public RestClient availabilityRestClient() {
        return RestClient.builder()
                .baseUrl(availabilityEngineUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
