package io.github.illuseahashmap.agent.mcp.application.port;

import java.time.Duration;

/**
 * Application boundary for protecting outbound MCP capacity.
 * Implementations must make admission and release atomic across workers.
 */
public interface McpResourceGovernance {

    Permit acquire(String tenantCode, long connectorVersionId, Duration timeout);

    void succeeded(Permit permit);

    void failed(Permit permit, McpFailureKind failureKind);

    record Permit(String tenantCode, String resourceKey, String leaseId) { }

    McpResourceGovernance NOOP = new McpResourceGovernance() {
        @Override
        public Permit acquire(String tenantCode, long connectorVersionId, Duration timeout) {
            return new Permit(tenantCode, String.valueOf(connectorVersionId), "noop");
        }

        @Override
        public void succeeded(Permit permit) { }

        @Override
        public void failed(Permit permit, McpFailureKind failureKind) { }
    };
}
