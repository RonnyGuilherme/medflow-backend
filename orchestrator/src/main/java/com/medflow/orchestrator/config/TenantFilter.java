package com.medflow.orchestrator.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Reads X-Tenant-ID and X-Correlation-ID headers injected by Kong Gateway
 * and stores them in ThreadLocal (TenantContext) and MDC (structured logging).
 *
 * Bypasses health and actuator endpoints that don't require tenant context.
 */
@Component
@Order(1)
@Slf4j
public class TenantFilter implements Filter {

    private static final String TENANT_HEADER       = "X-Tenant-ID";
    private static final String CORRELATION_HEADER  = "X-Correlation-ID";
    private static final String USER_ID_HEADER      = "X-User-ID";
    private static final String USER_ROLE_HEADER    = "X-User-Role";

    private static final Set<String> BYPASS_PATHS = Set.of(
        "/actuator", "/actuator/health", "/actuator/prometheus",
        "/actuator/info", "/swagger-ui", "/api-docs", "/health"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        boolean bypass = BYPASS_PATHS.stream().anyMatch(path::startsWith);
        if (bypass) {
            chain.doFilter(request, response);
            return;
        }

        String tenantId = httpRequest.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Request without X-Tenant-ID header rejected: {}", path);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            httpResponse.getWriter().write("{\"error\":\"X-Tenant-ID header is required\"}");
            return;
        }

        String correlationId = httpRequest.getHeader(CORRELATION_HEADER);
        String userId        = httpRequest.getHeader(USER_ID_HEADER);
        String userRole      = httpRequest.getHeader(USER_ROLE_HEADER);

        TenantContext.setTenantId(tenantId);
        MDC.put("tenantId", tenantId);
        if (correlationId != null) MDC.put("correlationId", correlationId);
        if (userId        != null) MDC.put("userId", userId);
        if (userRole      != null) MDC.put("userRole", userRole);

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }
}
