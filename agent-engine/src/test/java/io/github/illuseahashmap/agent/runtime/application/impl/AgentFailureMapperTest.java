package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import org.junit.jupiter.api.Test;

class AgentFailureMapperTest {

    @Test
    void mapsTransientMcpFailureToRetryableProviderCategory() {
        var failure = new AgentFailureMapper().fromMcp(new McpClientException(
                "MCP_RATE_LIMITED", McpFailureKind.RATE_LIMITED, true, "rate limited"));

        assertThat(failure.errorCode()).isEqualTo("MCP_RATE_LIMITED");
        assertThat(failure.category()).isEqualTo(AgentFailureCategory.PROVIDER_TRANSIENT);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void mapsAuthenticationFailureToNonRetryableToolProtocolCategory() {
        var failure = new AgentFailureMapper().fromMcp(new McpClientException(
                "MCP_AUTHENTICATION", McpFailureKind.AUTHENTICATION, false, "auth failed"));

        assertThat(failure.category()).isEqualTo(AgentFailureCategory.TOOL_PROTOCOL);
        assertThat(failure.retryable()).isFalse();
    }
}
