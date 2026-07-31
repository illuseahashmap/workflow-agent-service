package io.github.illuseahashmap.workflow.security;

public final class ServiceTokenContext {

    private static final ThreadLocal<ServiceTokenPrincipal> PRINCIPAL = new ThreadLocal<>();

    private ServiceTokenContext() {
    }

    public static void set(ServiceTokenPrincipal principal) {
        PRINCIPAL.set(principal);
    }

    public static ServiceTokenPrincipal current() {
        ServiceTokenPrincipal principal = PRINCIPAL.get();
        if (principal == null) {
            throw new IllegalStateException("Service token context is missing");
        }
        return principal;
    }

    public static void clear() {
        PRINCIPAL.remove();
    }

    public record ServiceTokenPrincipal(String clientCode, int tokenVersion) {
    }
}
