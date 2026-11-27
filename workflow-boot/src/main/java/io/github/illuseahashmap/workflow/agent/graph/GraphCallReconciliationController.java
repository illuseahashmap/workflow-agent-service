package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallReconciliation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallReconciliation.Evidence;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/agent-runtime/call-reconciliations")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class GraphCallReconciliationController {
    private final JdbcGraphCallReconciliation store;

    public GraphCallReconciliationController(JdbcGraphCallReconciliation store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> pending() {
        return ApiResponse.ok(TrustedDataAccessContext.runAsSystemWorker(store::pending));
    }

    @PostMapping
    public ApiResponse<UUID> propose(@RequestBody Evidence command, Authentication authentication) {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(
                        () -> store.propose(command, actor(authentication))));
    }

    public record Approval(String evidenceSha256) {}

    @PostMapping("/{id}/approve")
    public ApiResponse<Boolean> approve(
            @PathVariable UUID id, @RequestBody Approval command, Authentication authentication) {
        return ApiResponse.ok(
                TrustedDataAccessContext.runAsSystemWorker(
                        () -> store.approve(id, command.evidenceSha256(), actor(authentication))));
    }

    private String actor(Authentication authentication) {
        return authentication.getPrincipal() instanceof CurrentPrincipal principal
                ? principal.principalId()
                : authentication.getName();
    }
}
