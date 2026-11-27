package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Idempotent recovery trigger for XXL-Job or another external scheduler. */
@RestController
@RequestMapping("/internal/jobs/outbox")
@PreAuthorize("hasRole('SERVICE') or hasRole('PLATFORM_ADMIN')")
public class OutboxRecoveryJobController {

    private final AgentFlowableCompletionCoordinator coordinator;

    public OutboxRecoveryJobController(AgentFlowableCompletionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/agent-completions/recover")
    public ApiResponse<Void> recoverAgentCompletions() {
        coordinator.requestDispatch();
        return ApiResponse.ok(null);
    }
}
