package com.medflow.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * TenantFilter is the HTTP-level entry point that extracts and validates the tenant context, acting as the first line of defense for tenant isolation.
 * claim: every request either carries a Kong-injected X-Tenant-ID or gets rejected
 * before it reaches a controller. These tests mock the Servlet API directly (no
 * Spring context needed) and pay special attention to two properties that are easy
 * to silently break during a refactor: TenantContext is populated BEFORE the chain
 * runs, and it's cleared in a finally block even when downstream code throws.
 */
@ExtendWith(MockitoExtension.class)
class TenantFilterTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void tearDown() {
        // Defensive cleanup in case an assertion fails mid-test before the filter's
        // own finally block would have run.
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void doFilter_withTenantHeader_setsContextDuringChainAndClearsAfterward() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(request.getHeader("X-Tenant-ID")).thenReturn(TENANT_ID);

        doAnswer(invocation -> {
            // Proves ordering: context must exist *while* downstream code runs, not just after.
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(MDC.get("tenantId")).isEqualTo(TENANT_ID);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // finally block must have run after chain.doFilter returned
        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void doFilter_withoutTenantHeader_returns400AndNeverInvokesChain() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(request.getHeader("X-Tenant-ID")).thenReturn(null);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(body.toString()).contains("X-Tenant-ID header is required");
        verifyNoInteractions(chain);
        assertThat(TenantContext.hasTenant()).isFalse();
    }

    @Test
    void doFilter_withBlankTenantHeader_returns400AndNeverInvokesChain() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(request.getHeader("X-Tenant-ID")).thenReturn("   ");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNoInteractions(chain);
    }

    @Test
    void doFilter_forBypassPath_skipsTenantCheckAndInvokesChainDirectly() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health");
        // Deliberately no X-Tenant-ID stub: if the filter read it on this path and got
        // Mockito's default null, it would incorrectly fall into the 400 branch below.

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
        assertThat(TenantContext.hasTenant()).isFalse(); // bypass never sets a tenant
    }

    @Test
    void doFilter_whenChainThrows_stillClearsTenantContextAndMDC() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(request.getHeader("X-Tenant-ID")).thenReturn(TENANT_ID);
        doThrow(new RuntimeException("downstream boom")).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream boom");

        // If this were a plain try/without-finally, a pooled thread would leak
        // tenant state into whatever request it serves next.
        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void doFilter_populatesMDCOnlyForOptionalHeadersThatAreActuallyPresent() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(request.getHeader("X-Tenant-ID")).thenReturn(TENANT_ID);
        when(request.getHeader("X-Correlation-ID")).thenReturn("corr-123");
        when(request.getHeader("X-User-ID")).thenReturn(null); // absent
        when(request.getHeader("X-User-Role")).thenReturn("admin");

        doAnswer(invocation -> {
            assertThat(MDC.get("correlationId")).isEqualTo("corr-123");
            assertThat(MDC.get("userId")).isNull();
            assertThat(MDC.get("userRole")).isEqualTo("admin");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}