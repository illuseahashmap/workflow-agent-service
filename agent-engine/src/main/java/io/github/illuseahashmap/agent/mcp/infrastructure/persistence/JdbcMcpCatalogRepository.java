package io.github.illuseahashmap.agent.mcp.infrastructure.persistence;

import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.domain.McpConnector;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolCatalogVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMcpCatalogRepository implements McpCatalogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMcpCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public McpConnector saveConnector(McpConnector connector) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_connector (tenant_code, connector_code, connector_name, status, created_by)
                VALUES (:tenantCode, :connectorCode, :connectorName, :status, :createdBy)
                RETURNING *
                """, Map.of("tenantCode", connector.tenantCode(), "connectorCode", connector.connectorCode(),
                "connectorName", connector.connectorName(), "status", connector.status(),
                "createdBy", connector.createdBy()), (rs, row) -> new McpConnector(
                rs.getLong("id"), rs.getString("tenant_code"), rs.getString("connector_code"),
                rs.getString("connector_name"), rs.getString("status"), rs.getString("created_by")));
    }

    @Override
    public McpConnectorVersion saveConnectorVersion(McpConnectorVersion version) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_connector_version
                    (tenant_code, connector_id, version, endpoint_url, protocol_version,
                     credential_ref, timeout_seconds, status, created_by)
                VALUES (:tenantCode, :connectorId, :version, :endpointUrl, :protocolVersion,
                        :credentialRef, :timeoutSeconds, :status, 'system')
                RETURNING *
                """, Map.ofEntries(
                Map.entry("tenantCode", version.tenantCode()), Map.entry("connectorId", version.connectorId()),
                Map.entry("version", version.version()), Map.entry("endpointUrl", version.endpointUrl()),
                Map.entry("protocolVersion", version.protocolVersion()),
                Map.entry("credentialRef", version.credentialRef() == null ? "" : version.credentialRef()),
                Map.entry("timeoutSeconds", version.timeoutSeconds()), Map.entry("status", version.status())),
                (rs, row) -> mapConnectorVersion(rs));
    }

    @Override
    public int nextConnectorVersion(String tenantCode, long connectorId) {
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM mcp_connector_version
                WHERE tenant_code = :tenantCode AND connector_id = :connectorId
                """, Map.of("tenantCode", tenantCode, "connectorId", connectorId), Integer.class);
        return version == null ? 1 : version;
    }

    @Override
    public Optional<McpConnectorVersion> findConnectorVersion(String tenantCode, long id) {
        return jdbc.query("SELECT * FROM mcp_connector_version WHERE tenant_code = :tenantCode AND id = :id",
                Map.of("tenantCode", tenantCode, "id", id), (rs, row) -> mapConnectorVersion(rs))
                .stream().findFirst();
    }

    @Override
    public McpToolCatalogVersion saveCatalog(McpToolCatalogVersion catalog) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_tool_catalog_version
                    (tenant_code, connector_version_id, status, content_fingerprint)
                VALUES (:tenantCode, :connectorVersionId, :status, :fingerprint)
                RETURNING *
                """, Map.of("tenantCode", catalog.tenantCode(), "connectorVersionId", catalog.connectorVersionId(),
                "status", catalog.status(), "fingerprint", catalog.contentFingerprint()),
                (rs, row) -> new McpToolCatalogVersion(rs.getLong("id"), rs.getString("tenant_code"),
                        rs.getLong("connector_version_id"), rs.getString("status"),
                        rs.getString("content_fingerprint")));
    }

    @Override
    public Optional<McpToolCatalogVersion> findCatalog(String tenantCode, long catalogVersionId) {
        return jdbc.query("SELECT * FROM mcp_tool_catalog_version WHERE tenant_code = :tenantCode AND id = :id",
                Map.of("tenantCode", tenantCode, "id", catalogVersionId), (rs, row) ->
                        new McpToolCatalogVersion(rs.getLong("id"), rs.getString("tenant_code"),
                                rs.getLong("connector_version_id"), rs.getString("status"),
                                rs.getString("content_fingerprint"))).stream().findFirst();
    }

    @Override
    public void saveSnapshots(List<McpToolSnapshot> snapshots) {
        for (McpToolSnapshot snapshot : snapshots) {
            jdbc.update("""
                    INSERT INTO mcp_tool_snapshot
                        (tenant_code, catalog_version_id, tool_name, description, input_schema,
                         schema_fingerprint, risk_level)
                    VALUES (:tenantCode, :catalogVersionId, :toolName, :description, :inputSchema,
                            :schemaFingerprint, :riskLevel)
                    """, Map.of("tenantCode", snapshot.tenantCode(), "catalogVersionId", snapshot.catalogVersionId(),
                    "toolName", snapshot.toolName(), "description", snapshot.description(),
                    "inputSchema", snapshot.inputSchema(), "schemaFingerprint", snapshot.schemaFingerprint(),
                    "riskLevel", snapshot.riskLevel()));
        }
    }

    @Override
    public List<McpToolSnapshot> findSnapshots(String tenantCode, long catalogVersionId) {
        return jdbc.query("SELECT * FROM mcp_tool_snapshot WHERE tenant_code = :tenantCode AND catalog_version_id = :id",
                Map.of("tenantCode", tenantCode, "id", catalogVersionId), (rs, row) -> mapSnapshot(rs));
    }

    @Override
    public Optional<McpToolSnapshot> findSnapshotByRegistryCode(String tenantCode, String registryToolCode) {
        if (registryToolCode == null || !registryToolCode.startsWith("mcp:")) {
            return Optional.empty();
        }
        int separator = registryToolCode.indexOf(':', 4);
        if (separator < 0) {
            return Optional.empty();
        }
        long snapshotId;
        try {
            snapshotId = Long.parseLong(registryToolCode.substring(4, separator));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT snapshot.*
                FROM mcp_tool_snapshot snapshot
                JOIN mcp_tool_catalog_version catalog
                  ON catalog.id = snapshot.catalog_version_id AND catalog.tenant_code = snapshot.tenant_code
                WHERE snapshot.tenant_code = :tenantCode AND snapshot.id = :id
                  AND catalog.status = 'PUBLISHED'
                """, Map.of("tenantCode", tenantCode, "id", snapshotId),
                (rs, row) -> mapSnapshot(rs)).stream().findFirst();
    }

    @Override
    public Optional<McpConnectorVersion> findConnectorVersionForSnapshot(String tenantCode, long snapshotId) {
        return jdbc.query("""
                SELECT connector_version.*
                FROM mcp_connector_version connector_version
                JOIN mcp_tool_catalog_version catalog
                  ON catalog.connector_version_id = connector_version.id
                 AND catalog.tenant_code = connector_version.tenant_code
                JOIN mcp_tool_snapshot snapshot
                  ON snapshot.catalog_version_id = catalog.id
                 AND snapshot.tenant_code = catalog.tenant_code
                WHERE snapshot.tenant_code = :tenantCode AND snapshot.id = :snapshotId
                """, Map.of("tenantCode", tenantCode, "snapshotId", snapshotId),
                (rs, row) -> mapConnectorVersion(rs)).stream().findFirst();
    }

    @Override
    public void publishCatalog(String tenantCode, long catalogVersionId, String reviewedBy) {
        jdbc.update("""
                UPDATE mcp_tool_catalog_version
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, reviewed_by = :reviewedBy
                WHERE tenant_code = :tenantCode AND id = :id AND status = 'DRAFT'
                """, Map.of("tenantCode", tenantCode, "id", catalogVersionId, "reviewedBy", reviewedBy));
    }

    @Override
    public void bindSnapshotToAgentVersion(String tenantCode, long agentVersionId, long snapshotId) {
        jdbc.update("""
                INSERT INTO agent_version_mcp_tool_binding (tenant_code, agent_version_id, tool_snapshot_id)
                SELECT :tenantCode, :agentVersionId, snapshot.id
                FROM mcp_tool_snapshot snapshot
                JOIN mcp_tool_catalog_version catalog
                  ON catalog.id = snapshot.catalog_version_id AND catalog.tenant_code = snapshot.tenant_code
                WHERE snapshot.tenant_code = :tenantCode AND snapshot.id = :snapshotId
                  AND catalog.status = 'PUBLISHED' AND snapshot.risk_level = 'READ_ONLY'
                ON CONFLICT DO NOTHING
                """, Map.of("tenantCode", tenantCode, "agentVersionId", agentVersionId,
                "snapshotId", snapshotId));
        int versionRows = jdbc.update("""
                UPDATE agent_definition_version version
                SET tool_set_json = (
                    SELECT CASE WHEN version.tool_set_json ? snapshot.registry_code
                                THEN version.tool_set_json
                                ELSE version.tool_set_json || to_jsonb(snapshot.registry_code)
                           END
                    FROM (
                        SELECT ('mcp:' || snapshot.id || ':' || snapshot.tool_name) AS registry_code
                        FROM mcp_tool_snapshot snapshot
                        JOIN mcp_tool_catalog_version catalog ON catalog.id = snapshot.catalog_version_id
                        WHERE snapshot.id = :snapshotId AND snapshot.tenant_code = :tenantCode
                    ) snapshot
                )
                WHERE version.tenant_code = :tenantCode AND version.id = :agentVersionId
                  AND version.status = 'DRAFT'
                  AND EXISTS (
                      SELECT 1
                      FROM agent_version_mcp_tool_binding binding
                      WHERE binding.tenant_code = :tenantCode
                        AND binding.agent_version_id = :agentVersionId
                        AND binding.tool_snapshot_id = :snapshotId
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM mcp_tool_snapshot published_snapshot
                      JOIN mcp_tool_catalog_version published_catalog
                        ON published_catalog.id = published_snapshot.catalog_version_id
                       AND published_catalog.tenant_code = published_snapshot.tenant_code
                      WHERE published_snapshot.tenant_code = :tenantCode
                        AND published_snapshot.id = :snapshotId
                        AND published_catalog.status = 'PUBLISHED'
                        AND published_snapshot.risk_level = 'READ_ONLY'
                  )
                """, Map.of("tenantCode", tenantCode, "agentVersionId", agentVersionId,
                "snapshotId", snapshotId));
        if (versionRows == 0) {
            throw new IllegalStateException("Only a published MCP snapshot can be bound to a draft Agent version");
        }
    }

    private McpConnectorVersion mapConnectorVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new McpConnectorVersion(rs.getLong("id"), rs.getString("tenant_code"),
                rs.getLong("connector_id"), rs.getInt("version"), rs.getString("endpoint_url"),
                rs.getString("protocol_version"), rs.getString("credential_ref"),
                rs.getInt("timeout_seconds"), rs.getString("status"));
    }

    private McpToolSnapshot mapSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new McpToolSnapshot(rs.getLong("id"), rs.getString("tenant_code"),
                rs.getLong("catalog_version_id"), rs.getString("tool_name"), rs.getString("description"),
                rs.getString("input_schema"), rs.getString("schema_fingerprint"), rs.getString("risk_level"));
    }
}
