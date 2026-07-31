package io.github.illuseahashmap.workflow.tenant;

public final class TenantContext {

    private static final ThreadLocal<TenantInfo> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo tenantInfo) {
        TENANT.set(tenantInfo);
    }

    public static TenantInfo current() {
        TenantInfo tenantInfo = TENANT.get();
        if (tenantInfo == null) {
            throw new IllegalStateException("Tenant context is missing");
        }
        return tenantInfo;
    }

    public static void clear() {
        TENANT.remove();
    }

    public record TenantInfo(String tenantId, String tenantCode, String tenantName) {
    }
}
