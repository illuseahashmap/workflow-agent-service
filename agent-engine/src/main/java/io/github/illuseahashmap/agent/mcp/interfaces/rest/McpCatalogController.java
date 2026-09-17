package io.github.illuseahashmap.agent.mcp.interfaces.rest;

import io.github.illuseahashmap.agent.mcp.application.McpCatalogService;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.dto.McpDiscoveryView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorVersionView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public ApiResponse<PageResult<McpConnectorSummaryView>> list(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") Integer pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.ok(service.page(tenantProvider.current().tenantCode(), pageNum, pageSize));
    }

    @PostMapping("/connectors")
    public ApiResponse<McpConnectorVersionView> create(@Valid @RequestBody McpConnectorCommand command) {
        return ApiResponse.ok(service.create(command));
    }

    @DeleteMapping("/connectors/{id}")
    public ApiResponse<Void> deleteDraftConnector(@PathVariable long id) {
        service.deleteDraftConnector(id);
        return ApiResponse.ok(null);
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

    @org.springframework.web.bind.annotation.DeleteMapping("/agent-versions/{agentVersionId}/tools/{toolSnapshotId}")
    public ApiResponse<Void> unbind(@PathVariable long agentVersionId, @PathVariable long toolSnapshotId) {
        service.unbind(agentVersionId, toolSnapshotId);
        return ApiResponse.ok(null);
    }

    @org.springframework.web.bind.annotation.GetMapping("/catalog-versions/{catalogVersionId}/tools")
    public ApiResponse<List<McpDiscoveryView.ToolView>> publishedTools(@PathVariable long catalogVersionId) {
        return ApiResponse.ok(service.publishedTools(catalogVersionId));
    }
}
