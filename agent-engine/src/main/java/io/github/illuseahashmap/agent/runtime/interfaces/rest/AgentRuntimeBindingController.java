package io.github.illuseahashmap.agent.runtime.interfaces.rest;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeBinding;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeBinding.Configuration;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agents/versions/{versionId}/runtime")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:manage')")
public class AgentRuntimeBindingController {
    private final AgentRuntimeBinding store;
    private final TenantProvider tenant;
    private final CurrentPrincipalProvider principal;

    public AgentRuntimeBindingController(
            AgentRuntimeBinding store, TenantProvider tenant, CurrentPrincipalProvider principal) {
        this.store = store;
        this.tenant = tenant;
        this.principal = principal;
    }

    @GetMapping
    public ApiResponse<Configuration> read(@PathVariable long versionId) {
        return ApiResponse.ok(store.read(tenant.current().tenantCode(), versionId));
    }

    @PutMapping
    public ApiResponse<Configuration> configure(
            @PathVariable long versionId, @RequestBody Configuration command) {
        return ApiResponse.ok(
                store.configure(
                        tenant.current().tenantCode(),
                        versionId,
                        command,
                        principal.current().principalId()));
    }
}
