package io.github.illuseahashmap.agent.mcp.infrastructure.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "workflow.agent.mcp.governance")
public class RedisMcpResourceGovernanceProperties {
    private int maxConcurrentPerTenant = 2;
    private int requestsPerMinutePerTenant = 60;
    private int burstPerTenant = 10;
    private int circuitFailureThreshold = 5;
    private long circuitOpenSeconds = 30;
    private long permitSeconds = 180;

    public int getMaxConcurrentPerTenant() { return maxConcurrentPerTenant; }
    public void setMaxConcurrentPerTenant(int value) { maxConcurrentPerTenant = Math.max(1, Math.min(value, 10_000)); }
    public int getRequestsPerMinutePerTenant() { return requestsPerMinutePerTenant; }
    public void setRequestsPerMinutePerTenant(int value) { requestsPerMinutePerTenant = Math.max(1, Math.min(value, 1_000_000)); }
    public int getBurstPerTenant() { return burstPerTenant; }
    public void setBurstPerTenant(int value) { burstPerTenant = Math.max(1, Math.min(value, 100_000)); }
    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int value) { circuitFailureThreshold = Math.max(1, Math.min(value, 100)); }
    public long getCircuitOpenSeconds() { return circuitOpenSeconds; }
    public void setCircuitOpenSeconds(long value) { circuitOpenSeconds = Math.max(1, Math.min(value, 86_400)); }
    public long getPermitSeconds() { return permitSeconds; }
    public void setPermitSeconds(long value) { permitSeconds = Math.max(5, Math.min(value, 3_600)); }
}
