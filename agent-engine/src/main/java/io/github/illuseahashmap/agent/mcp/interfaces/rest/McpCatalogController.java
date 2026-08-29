package io.github.illuseahashmap.agent.mcp.interfaces.rest;

import io.github.illuseahashmap.agent.mcp.application.McpCatalogService;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.dto.McpDiscoveryView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorVersionView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorSummaryView;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;

@RestController
@RequestMapping("/mcp")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:manage')")
public class McpCatalogController {

    private final McpCatalogService service;
    private final TenantProvider tenantProvider;

    public McpCatalogController(McpCatalogService service, TenantProvider tenantProvider) {
        this.service = service;
        this.tenantProvider = tenantProvider;
    }

    @org.springframework.web.bind.annotation.GetMapping("/connectors")
    public ApiResponse<List<McpConnectorSummaryView>> list() {
        return ApiResponse.ok(service.list(tenantProvider.current().tenantCode()));
    }

    @PostMapping("/connectors")
    public ApiResponse<McpConnectorVersionView> create(@Valid @RequestBody McpConnectorCommand command) {
        return ApiResponse.ok(service.create(command));
    }

    @PostMapping("/connector-versions/{id}/discover")
    public ApiResponse<McpDiscoveryView> discover(@PathVariable long id) {
        return ApiResponse.ok(service.discover(id));
    }

    @PostMapping("/catalog-versions/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable long id) {
        service.publish(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/agent-versions/{agentVersionId}/tools/{toolSnapshotId}")
    public ApiResponse<Void> bind(@PathVariable long agentVersionId, @PathVariable long toolSnapshotId) {
        service.bind(agentVersionId, toolSnapshotId);
        return ApiResponse.ok(null);
    }
}
