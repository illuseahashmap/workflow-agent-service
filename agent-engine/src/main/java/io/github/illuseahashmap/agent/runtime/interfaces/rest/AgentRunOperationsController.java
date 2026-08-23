package io.github.illuseahashmap.agent.runtime.interfaces.rest;

import io.github.illuseahashmap.agent.runtime.application.AgentRunOperationsService;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-runs")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:run:execute')")
public class AgentRunOperationsController {

    private final AgentRunOperationsService service;

    public AgentRunOperationsController(AgentRunOperationsService service) {
        this.service = service;
    }

    @PostMapping("/{runId}/retry")
    public ApiResponse<Void> retry(
            @PathVariable long runId,
            @Valid @RequestBody RetryAgentRunCommand command
    ) {
        service.retryFailed(runId, command.reason(), command.retryWindowSeconds());
        return ApiResponse.ok(null);
    }

    public record RetryAgentRunCommand(
            @NotBlank @Size(max = 1000) String reason,
            @Min(30) @Max(3600) int retryWindowSeconds
    ) {
    }
}
