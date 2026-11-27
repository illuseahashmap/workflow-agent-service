package io.github.illuseahashmap.agent.mcp.infrastructure.governance;

import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.mcp.application.port.McpResourceGovernance;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-atomic tenant admission, token bucket, circuit breaker and lease release. */
@Component
public class RedisMcpResourceGovernance implements McpResourceGovernance {
    private static final String PREFIX = "workflow:mcp:governance:v1:";
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local maxConcurrent = tonumber(ARGV[2])
            local ratePerMs = tonumber(ARGV[3])
            local burst = tonumber(ARGV[4])
            local permitTtl = tonumber(ARGV[5])
            local openUntil = tonumber(redis.call('HGET', KEYS[2], 'openUntil') or '0')
            local probe = 0
            if openUntil > now then return 0 end
            if openUntil > 0 then
              if redis.call('HGET', KEYS[2], 'probe') == '1' then return 0 end
              probe = 1
            end
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or tostring(burst))
            local refillAt = tonumber(redis.call('HGET', KEYS[1], 'refillAt') or tostring(now))
            tokens = math.min(burst, tokens + math.max(0, now - refillAt) * ratePerMs)
            local inflight = tonumber(redis.call('HGET', KEYS[1], 'inflight') or '0')
            if inflight >= maxConcurrent then return -2 end
            if tokens < 1 then return -3 end
            if probe == 1 then redis.call('HSET', KEYS[2], 'probe', '1') end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens - 1), 'refillAt', tostring(now), 'inflight', tostring(inflight + 1))
            redis.call('PEXPIRE', KEYS[1], permitTtl)
            redis.call('SET', KEYS[3], tostring(probe), 'PX', permitTtl)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> SUCCESS = new DefaultRedisScript<>("""
            if redis.call('DEL', KEYS[1]) == 1 then
              local inflight = math.max(0, tonumber(redis.call('HGET', KEYS[2], 'inflight') or '1') - 1)
              redis.call('HSET', KEYS[2], 'inflight', tostring(inflight))
              redis.call('HSET', KEYS[3], 'failures', '0', 'openUntil', '0', 'probe', '0')
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            local probe = redis.call('GET', KEYS[1])
            if redis.call('DEL', KEYS[1]) == 1 then
              local inflight = math.max(0, tonumber(redis.call('HGET', KEYS[2], 'inflight') or '1') - 1)
              redis.call('HSET', KEYS[2], 'inflight', tostring(inflight))
            end
            if ARGV[1] == '1' or probe == '1' then
              local failures = tonumber(redis.call('HGET', KEYS[3], 'failures') or '0') + 1
              redis.call('HSET', KEYS[3], 'failures', tostring(failures), 'probe', '0')
              if failures >= tonumber(ARGV[2]) then
                redis.call('HSET', KEYS[3], 'openUntil', tostring(tonumber(ARGV[3]) + tonumber(ARGV[4])))
              end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisMcpResourceGovernanceProperties properties;

    public RedisMcpResourceGovernance(StringRedisTemplate redis,
                                      RedisMcpResourceGovernanceProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Permit acquire(String tenantCode, long connectorVersionId, Duration timeout) {
        String tenantKey = PREFIX + "tenant:" + stable(tenantCode);
        String connectorKey = PREFIX + "connector:" + stable(tenantCode) + ':' + connectorVersionId;
        String leaseId = UUID.randomUUID().toString();
        String permitKey = PREFIX + "permit:" + leaseId;
        long now = System.currentTimeMillis();
        try {
            Long result = redis.execute(ACQUIRE, List.of(tenantKey, connectorKey, permitKey),
                    Long.toString(now), Integer.toString(properties.getMaxConcurrentPerTenant()),
                    Double.toString(properties.getRequestsPerMinutePerTenant() / 60_000.0),
                    Integer.toString(properties.getBurstPerTenant()),
                    Long.toString(Math.max(1_000L, Math.min(properties.getPermitSeconds() * 1_000L,
                            timeout.toMillis() + 5_000L))));
            if (result != null && result == 1L) {
                return new Permit(tenantCode, connectorKey, leaseId);
            }
            String code = result != null && result == -3L ? "MCP_RATE_LIMITED" : "MCP_TENANT_QUOTA_EXCEEDED";
            throw new McpClientException(code, McpFailureKind.RATE_LIMITED, true,
                    result != null && result == -3L ? "MCP tenant rate limit exceeded" : "MCP tenant concurrent quota exceeded");
        } catch (McpClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new McpClientException("MCP_GOVERNANCE_UNAVAILABLE", McpFailureKind.UNAVAILABLE, true,
                    "MCP admission control is unavailable", exception);
        }
    }

    @Override
    public void succeeded(Permit permit) {
        run(SUCCESS, List.of(permitKey(permit), tenantKey(permit), permit.resourceKey()));
    }

    @Override
    public void failed(Permit permit, McpFailureKind failureKind) {
        boolean transientFailure = failureKind == McpFailureKind.TIMEOUT
                || failureKind == McpFailureKind.UNAVAILABLE || failureKind == McpFailureKind.RATE_LIMITED;
        run(FAILURE, List.of(permitKey(permit), tenantKey(permit), permit.resourceKey()),
                transientFailure ? "1" : "0", Integer.toString(properties.getCircuitFailureThreshold()),
                Long.toString(System.currentTimeMillis()),
                Long.toString(properties.getCircuitOpenSeconds() * 1_000L));
    }

    private void run(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            redis.execute(script, keys, (Object[]) args);
        } catch (RuntimeException ignored) {
            // Cleanup must never mask the actual MCP result.
        }
    }

    private String permitKey(Permit permit) { return PREFIX + "permit:" + permit.leaseId(); }
    private String tenantKey(Permit permit) { return PREFIX + "tenant:" + stable(permit.tenantCode()); }
    private String stable(String value) { return Integer.toHexString(value == null ? 0 : value.hashCode()); }
}
