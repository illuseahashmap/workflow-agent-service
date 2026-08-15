package io.github.illuseahashmap.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void acceptsSafeCallerTraceAndClearsThreadContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.TRACE_HEADER, "trace-client-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) ->
                assertThat(CurrentTraceContext.currentOrNull()).isEqualTo("trace-client-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestTraceFilter.TRACE_HEADER)).isEqualTo("trace-client-1");
        assertThat(CurrentTraceContext.currentOrNull()).isNull();
    }

    @Test
    void replacesUnsafeTraceValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.TRACE_HEADER, "unsafe trace value\n");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getHeader(RequestTraceFilter.TRACE_HEADER))
                .isNotBlank()
                .doesNotContain("unsafe");
    }
}
