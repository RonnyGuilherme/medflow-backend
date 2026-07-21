package com.medflow.orchestrator.config;

/**
 * Thread-local holder for the current tenant ID.
 *
 * Populated by {@link TenantFilter} from the X-Tenant-ID header
 * injected by Kong after JWT validation. Services read from here
 * instead of accepting tenant IDs from caller inputs.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Tenant ID not set in current context");
        }
        return tenantId;
    }

    public static boolean hasTenant() {
        String t = CURRENT_TENANT.get();
        return t != null && !t.isBlank();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
