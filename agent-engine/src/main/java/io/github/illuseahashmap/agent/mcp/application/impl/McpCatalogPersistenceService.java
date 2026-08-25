package io.github.illuseahashmap.agent.mcp.application.impl;

import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.domain.McpToolCatalogVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a discovered catalog and all snapshots as one local transaction. */
@Service
public class McpCatalogPersistenceService {

    private final McpCatalogRepository repository;

    public McpCatalogPersistenceService(McpCatalogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Result persist(String tenantCode, long connectorVersionId, String fingerprint,
                          List<McpToolSnapshot> snapshots) {
        McpToolCatalogVersion catalog = repository.saveCatalog(new McpToolCatalogVersion(
                null, tenantCode, connectorVersionId, "DRAFT", fingerprint));
        List<McpToolSnapshot> persisted = snapshots.stream().map(snapshot -> new McpToolSnapshot(
                null, tenantCode, catalog.id(), snapshot.toolName(), snapshot.description(),
                snapshot.inputSchema(), snapshot.schemaFingerprint(), snapshot.riskLevel())).toList();
        repository.saveSnapshots(persisted);
        return new Result(catalog, repository.findSnapshots(tenantCode, catalog.id()));
    }

    public record Result(McpToolCatalogVersion catalog, List<McpToolSnapshot> snapshots) {
    }
}
