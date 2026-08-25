package io.github.illuseahashmap.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAgentToolAdapterTest {

    @Test
    void mapsPublishedSnapshotToReadOnlyToolCall() {
        McpCatalogRepository repository = mock(McpCatalogRepository.class);
        McpClientPort client = mock(McpClientPort.class);
        McpToolSnapshot snapshot = new McpToolSnapshot(7L, "tenant-a", 3L, "employee_directory",
                "lookup", "{\"type\":\"object\"}", "schema-hash", "READ_ONLY");
        McpConnectorVersion connector = new McpConnectorVersion(4L, "tenant-a", 2L, 1,
                "https://mcp.example.test/tools", "2025-03-26", null, 10, "PUBLISHED");
        when(repository.findSnapshotByRegistryCode("tenant-a", snapshot.registryToolCode()))
                .thenReturn(java.util.Optional.of(snapshot));
        when(repository.findConnectorVersionForSnapshot("tenant-a", snapshot.id()))
                .thenReturn(java.util.Optional.of(connector));
        when(client.initialize(eq(connector), any(Duration.class)))
                .thenReturn(new McpClientPort.Session(connector, "session-1", "2025-03-26"));
        when(client.callTool(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("employee_directory"),
                eq(Map.of("employee", "zhang")), any(Duration.class)))
                .thenReturn(new McpClientPort.CallResult("[{\"manager\":\"li\"}]", false));

        AgentTool tool = new McpAgentToolAdapter(repository, client)
                .resolve(snapshot.registryToolCode()).orElseThrow();
        AgentTool.Result result = tool.execute(new AgentTool.Request("tenant-a", Map.of("employee", "zhang"),
                Duration.ofSeconds(5), "trace-1", "idempotency-1", "process-1", 1L, "tool:1"));

        assertThat(result.output()).contains("li");
        assertThat(result.idempotencyKey()).isEqualTo("idempotency-1");
        var initializeTimeout = org.mockito.ArgumentCaptor.forClass(Duration.class);
        var callTimeout = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(client).initialize(eq(connector), initializeTimeout.capture());
        verify(client).callTool(any(), anyString(), eq(Map.of("employee", "zhang")), callTimeout.capture());
        assertThat(initializeTimeout.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(callTimeout.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
