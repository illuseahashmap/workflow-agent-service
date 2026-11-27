package io.github.illuseahashmap.agent.mcp.infrastructure.session;

import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpSessionCache;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Small, bounded per-process cache. A new worker always initializes a fresh session,
 * so a process restart cannot make recovery depend on stale memory.
 */
@Component
public class InMemoryMcpSessionCache implements McpSessionCache {

    private final int maxEntries;
    private final Duration ttl;
    private final Map<String, Entry> entries;

    public InMemoryMcpSessionCache(
            @Value("${workflow.agent.mcp.session-cache.max-entries:256}") int maxEntries,
            @Value("${workflow.agent.mcp.session-cache.ttl-seconds:300}") long ttlSeconds) {
        this.maxEntries = Math.max(1, Math.min(maxEntries, 10_000));
        this.ttl = Duration.ofSeconds(Math.max(1, Math.min(ttlSeconds, 86_400)));
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public synchronized Optional<McpClientPort.Session> find(
            McpConnectorVersion connector, String credentialFingerprint) {
        evictExpired();
        Entry entry = entries.get(key(connector, credentialFingerprint));
        return entry == null ? Optional.empty() : Optional.of(entry.session());
    }

    @Override
    public synchronized void save(McpClientPort.Session session) {
        if (session.sessionId() == null || session.sessionId().isBlank()) {
            return;
        }
        evictExpired();
        invalidateConnectorInternal(session.connector());
        entries.put(key(session.connector(), session.credentialFingerprint()),
                new Entry(session, Instant.now().plus(ttl)));
        while (entries.size() > maxEntries) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    @Override
    public synchronized void invalidate(McpClientPort.Session session) {
        entries.remove(key(session.connector(), session.credentialFingerprint()));
    }

    @Override
    public synchronized void invalidateConnector(McpConnectorVersion connector) {
        invalidateConnectorInternal(connector);
    }

    private void invalidateConnectorInternal(McpConnectorVersion connector) {
        entries.entrySet().removeIf(entry -> sameConnector(entry.getValue().session().connector(), connector));
    }

    private void evictExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().expiresAt())) {
                iterator.remove();
            }
        }
    }

    private boolean sameConnector(McpConnectorVersion left, McpConnectorVersion right) {
        return java.util.Objects.equals(left.id(), right.id()) && left.version() == right.version();
    }

    private String key(McpConnectorVersion connector, String fingerprint) {
        return connector.tenantCode() + ':' + connector.id() + ':' + connector.version() + ':' + fingerprint;
    }

    private record Entry(McpClientPort.Session session, Instant expiresAt) { }
}
