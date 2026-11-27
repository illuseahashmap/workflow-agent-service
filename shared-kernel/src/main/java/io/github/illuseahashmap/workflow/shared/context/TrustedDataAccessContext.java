package io.github.illuseahashmap.workflow.shared.context;

import java.util.function.Supplier;

/**
 * Marks a bounded, server-owned operation that legitimately spans tenants.
 * The context is deliberately explicit and scoped to one call stack.
 */
public final class TrustedDataAccessContext {

    private static final ThreadLocal<Boolean> SYSTEM_WORKER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTHENTICATION = new ThreadLocal<>();

    private TrustedDataAccessContext() {
    }

    public static boolean isSystemWorker() {
        return Boolean.TRUE.equals(SYSTEM_WORKER.get());
    }

    /**
     * Marks the narrow authentication bootstrap phase before a tenant is known.
     * It must only surround user/tenant/authorization lookup, never business work.
     */
    public static boolean isAuthentication() {
        return Boolean.TRUE.equals(AUTHENTICATION.get());
    }

    public static <T> T runAsAuthentication(Supplier<T> action) {
        Boolean previous = AUTHENTICATION.get();
        AUTHENTICATION.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            restoreAuthentication(previous);
        }
    }

    public static void runAsAuthentication(Runnable action) {
        runAsAuthentication(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T runAsSystemWorker(Supplier<T> action) {
        Boolean previous = SYSTEM_WORKER.get();
        SYSTEM_WORKER.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAsSystemWorker(Runnable action) {
        runAsSystemWorker(() -> {
            action.run();
            return null;
        });
    }

    private static void restore(Boolean previous) {
        if (previous == null) {
            SYSTEM_WORKER.remove();
        } else {
            SYSTEM_WORKER.set(previous);
        }
    }

    private static void restoreAuthentication(Boolean previous) {
        if (previous == null) {
            AUTHENTICATION.remove();
        } else {
            AUTHENTICATION.set(previous);
        }
    }
}
