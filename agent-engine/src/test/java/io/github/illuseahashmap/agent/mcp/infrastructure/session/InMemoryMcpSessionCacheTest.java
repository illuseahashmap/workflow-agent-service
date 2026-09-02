package io.github.illuseahashmap.agent.mcp.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryMcpSessionCacheTest {

    @Test
    void credentialRotationReplacesThePreviousConnectorSession() {
        InMemoryMcpSessionCache cache = new InMemoryMcpSessionCache(4, 60);
        McpConnectorVersion connector = connector();
        McpClientPort.Session oldSession = new McpClientPort.Session(connector, "old", "2025-03-26", "old-hash");
        McpClientPort.Session newSession = new McpClientPort.Session(connector, "new", "2025-03-26", "new-hash");

        cache.save(oldSession);
        cache.save(newSession);

        assertThat(cache.find(connector, "old-hash")).isEmpty();
        assertThat(cache.find(connector, "new-hash")).contains(newSession);
    }

    @Test
    void cacheIsBoundedAndExpiredEntriesAreNotReturned() throws Exception {
        InMemoryMcpSessionCache cache = new InMemoryMcpSessionCache(1, 1);
        McpConnectorVersion connector = connector();
        cache.save(new McpClientPort.Session(connector, "session", "2025-03-26", "hash"));

        Thread.sleep(Duration.ofMillis(1_100));

        assertThat(cache.find(connector, "hash")).isEmpty();
    }

    private McpConnectorVersion connector() {
        return new McpConnectorVersion(1L, "tenant-a", 1L, 1,
                "https://mcp.example.test/mcp", "2025-03-26", null, 10, "PUBLISHED");
    }
}
