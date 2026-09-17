package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes one validated Trace ID for logs, API responses and business audit records. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = traceId(request.getHeader(TRACE_HEADER));
        CurrentTraceContext.set(traceId);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            CurrentTraceContext.clear();
        }
    }

    private String traceId(String requested) {
        if (requested != null && SAFE_TRACE_ID.matcher(requested).matches()) {
            return requested;
        }
        return UUID.randomUUID().toString();
    }
}
