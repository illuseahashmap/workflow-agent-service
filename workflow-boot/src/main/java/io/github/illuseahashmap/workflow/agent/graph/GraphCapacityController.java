package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration.PlatformUpdate;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration.TenantUpdate;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration.View;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuration remains available while graph workers are disabled. */
@RestController
@RequestMapping("/agent-runtime/capacity")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class GraphCapacityController {
    private final JdbcGraphCapacityConfiguration capacity;

    public GraphCapacityController(JdbcGraphCapacityConfiguration capacity) {
        this.capacity = capacity;
    }

    @GetMapping
    public ApiResponse<View> platform() {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(() -> capacity.read(null)));
    }

    @PutMapping
    public ApiResponse<View> updatePlatform(
            @RequestBody PlatformUpdate command, Authentication authentication) {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(
                        () -> capacity.updatePlatform(command, actor(authentication))));
    }

    @GetMapping("/tenants/{tenantCode}")
    public ApiResponse<View> tenant(@PathVariable String tenantCode) {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(() -> capacity.read(tenantCode)));
    }

    @PutMapping("/tenants/{tenantCode}")
    public ApiResponse<View> updateTenant(
            @PathVariable String tenantCode,
            @RequestBody TenantUpdate command,
            Authentication authentication) {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(
                        () -> capacity.updateTenant(tenantCode, command, actor(authentication))));
    }

    private String actor(Authentication authentication) {
        return authentication.getPrincipal() instanceof CurrentPrincipal principal
                ? principal.principalId()
                : authentication.getName();
    }
}
