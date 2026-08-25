package io.github.illuseahashmap.agent.mcp.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.McpCatalogService;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.dto.McpDiscoveryView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorVersionView;
import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpToolRegistrationPort;
import io.github.illuseahashmap.agent.mcp.domain.McpConnector;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolCatalogVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpCatalogServiceImpl implements McpCatalogService {

    private final McpCatalogRepository repository;
    private final McpClientPort client;
    private final McpToolRegistrationPort registration;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;
    private final ObjectMapper objectMapper;
    private final AgentOutputSchemaValidator schemaValidator;

    public McpCatalogServiceImpl(McpCatalogRepository repository, McpClientPort client,
                                 McpToolRegistrationPort registration, TenantProvider tenantProvider,
                                 CurrentPrincipalProvider principalProvider, ObjectMapper objectMapper,
                                 AgentOutputSchemaValidator schemaValidator) {
        this.repository = repository;
        this.client = client;
        this.registration = registration;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
    }

    @Override
    @Transactional
    public McpConnectorVersionView create(McpConnectorCommand command) {
        String tenant = tenantProvider.current().tenantCode();
        String principal = principalProvider.current().username();
        McpConnector connector = repository.saveConnector(new McpConnector(null, tenant,
                command.connectorCode().trim(), command.connectorName().trim(), "DRAFT", principal));
        int timeout = command.timeoutSeconds() == null ? 30 : command.timeoutSeconds();
        McpConnectorVersion version = repository.saveConnectorVersion(new McpConnectorVersion(null, tenant, connector.id(),
                repository.nextConnectorVersion(tenant, connector.id()), command.endpointUrl().trim(),
                command.protocolVersion() == null || command.protocolVersion().isBlank()
                        ? "2025-03-26" : command.protocolVersion(), command.credentialRef(), timeout, "DRAFT"));
        return new McpConnectorVersionView(version.id(), version.connectorId(), version.version(),
                version.endpointUrl(), version.protocolVersion(), version.status(), version.timeoutSeconds());
    }

    @Override
    public McpDiscoveryView discover(long connectorVersionId) {
        String tenant = tenantProvider.current().tenantCode();
        McpConnectorVersion connector = repository.findConnectorVersion(tenant, connectorVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "MCP connector version not found"));
        McpClientPort.Session session = client.initialize(connector, Duration.ofSeconds(connector.timeoutSeconds()));
        List<McpClientPort.Tool> tools = client.listTools(session, Duration.ofSeconds(connector.timeoutSeconds()));
        tools.forEach(tool -> schemaValidator.validateDefinition(tool.inputSchema()));
        List<McpToolSnapshot> snapshots = tools.stream().map(tool -> new McpToolSnapshot(null, tenant, 0,
                tool.name(), tool.description(), tool.inputSchema(), fingerprint(tool.inputSchema()), "READ_ONLY")).toList();
        String fingerprint = fingerprint(tools.stream().map(tool -> tool.name() + tool.inputSchema()).sorted().toList().toString());
        McpToolCatalogVersion catalog = repository.saveCatalog(new McpToolCatalogVersion(null, tenant,
                connector.id(), "DRAFT", fingerprint));
        List<McpToolSnapshot> persisted = snapshots.stream().map(snapshot -> new McpToolSnapshot(null, tenant,
                catalog.id(), snapshot.toolName(), snapshot.description(), snapshot.inputSchema(),
                snapshot.schemaFingerprint(), snapshot.riskLevel())).toList();
        repository.saveSnapshots(persisted);
        List<McpToolSnapshot> loaded = repository.findSnapshots(tenant, catalog.id());
        return new McpDiscoveryView(catalog.id(), catalog.status(), fingerprint, loaded.stream()
                .map(snapshot -> new McpDiscoveryView.ToolView(snapshot.id(), snapshot.registryToolCode(),
                        snapshot.toolName(), snapshot.description(), snapshot.inputSchema())).toList());
    }

    @Override
    @Transactional
    public void publish(long catalogVersionId) {
        String tenant = tenantProvider.current().tenantCode();
        String reviewer = principalProvider.current().username();
        McpToolCatalogVersion catalog = repository.findCatalog(tenant, catalogVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "MCP catalog version not found"));
        if (!"DRAFT".equals(catalog.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "MCP catalog version is not a draft");
        }
        List<McpToolSnapshot> snapshots = repository.findSnapshots(tenant, catalogVersionId);
        if (snapshots.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP catalog cannot publish without tools");
        }
        repository.publishCatalog(tenant, catalogVersionId, reviewer);
        snapshots.forEach(snapshot -> registration.registerReadOnlyTool(tenant, snapshot));
    }

    @Override
    @Transactional
    public void bind(long agentVersionId, long toolSnapshotId) {
        repository.bindSnapshotToAgentVersion(tenantProvider.current().tenantCode(), agentVersionId, toolSnapshotId);
    }

    private String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint MCP catalog", exception);
        }
    }
}
