package com.medflow.orchestrator.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests — no mocks, no Spring context. TenantContext is the ThreadLocal
 * every repository query and service method reads from instead of trusting caller
 * input, so its "fail closed when unset" behavior is the actual enforcement point
 * behind the README's tenant-isolation claim. TenantFilterTest covers how this
 * gets populated from the HTTP layer; this covers the primitive itself in isolation.
 */
class TenantContextTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @AfterEach
    void tearDown() {
        // Defensive — avoids leaking tenant state into other tests on the same thread
        TenantContext.clear();
    }

    @Test
    void getTenantId_afterSetTenantId_returnsTheSameValue() {
        TenantContext.setTenantId(TENANT_ID);

        assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void getTenantId_whenNeverSet_throwsIllegalStateException() {
        // No setTenantId() call — simulates a code path that forgot to go through TenantFilter
        assertThatThrownBy(TenantContext::getTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant ID not set");
    }

    @Test
    void getTenantId_whenSetToBlank_throwsIllegalStateException() {
        // An empty/whitespace header value must fail closed, not silently proceed as "no filter"
        TenantContext.setTenantId("   ");

        assertThatThrownBy(TenantContext::getTenantId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hasTenant_reflectsWhetherATenantIsCurrentlySet() {
        assertThat(TenantContext.hasTenant()).isFalse();

        TenantContext.setTenantId(TENANT_ID);
        assertThat(TenantContext.hasTenant()).isTrue();
    }

    @Test
    void hasTenant_whenSetToBlank_returnsFalse() {
        TenantContext.setTenantId("");

        assertThat(TenantContext.hasTenant()).isFalse();
    }

    @Test
    void clear_removesTenantSoSubsequentGetThrows() {
        TenantContext.setTenantId(TENANT_ID);
        assertThat(TenantContext.hasTenant()).isTrue();

        TenantContext.clear();

        assertThat(TenantContext.hasTenant()).isFalse();
        assertThatThrownBy(TenantContext::getTenantId)
                .isInstanceOf(IllegalStateException.class);
    }
}