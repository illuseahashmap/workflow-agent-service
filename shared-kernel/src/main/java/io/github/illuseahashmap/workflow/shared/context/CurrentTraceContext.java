package io.github.illuseahashmap.workflow.shared.context;

/** Request-scoped correlation identifier without coupling business modules to logging libraries. */
public final class CurrentTraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private CurrentTraceContext() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String currentOrNull() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
