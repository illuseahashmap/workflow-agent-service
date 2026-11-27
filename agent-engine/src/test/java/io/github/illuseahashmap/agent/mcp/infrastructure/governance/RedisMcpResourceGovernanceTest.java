package io.github.illuseahashmap.agent.mcp.infrastructure.governance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.mcp.application.port.McpResourceGovernance;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisMcpResourceGovernanceTest {

    @Test
    void rejectsRateLimitedAndQuotaClaimsAsRetryable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(-3L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisMcpResourceGovernance governance = new RedisMcpResourceGovernance(redis, properties());

        assertThatThrownBy(() -> governance.acquire("tenant-a", 7L, Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(McpClientException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode()).isEqualTo("MCP_RATE_LIMITED");
                    org.assertj.core.api.Assertions.assertThat(exception.failureKind()).isEqualTo(McpFailureKind.RATE_LIMITED);
                    org.assertj.core.api.Assertions.assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void releasesAClaimThroughTheAtomicCompletionScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisMcpResourceGovernance governance = new RedisMcpResourceGovernance(redis, properties());

        McpResourceGovernance.Permit permit = governance.acquire("tenant-a", 7L, Duration.ofSeconds(5));
        governance.succeeded(permit);

        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private RedisMcpResourceGovernanceProperties properties() {
        RedisMcpResourceGovernanceProperties properties = new RedisMcpResourceGovernanceProperties();
        properties.setMaxConcurrentPerTenant(2);
        properties.setRequestsPerMinutePerTenant(60);
        properties.setBurstPerTenant(2);
        return properties;
    }
}
