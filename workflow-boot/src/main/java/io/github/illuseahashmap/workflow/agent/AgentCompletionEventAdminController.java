package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Restricted recovery commands for poisoned Agent completion events. */
@Validated
@RestController
@RequestMapping("/operations/agent-completion-events")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AgentCompletionEventAdminController {

    private final AgentCompletionOperationsService operationsService;

    public AgentCompletionEventAdminController(AgentCompletionOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/dead-letters")
    public ApiResponse<List<AgentCompletionEventStore.DeadLetterEvent>> deadLetters(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return ApiResponse.ok(operationsService.deadLetters(limit));
    }

    @PostMapping("/{eventId}/replay")
    public ApiResponse<Void> replay(@PathVariable UUID eventId) {
        operationsService.replay(eventId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{eventId}/ignore")
    public ApiResponse<Void> ignore(@PathVariable UUID eventId) {
        operationsService.ignore(eventId);
        return ApiResponse.ok(null);
    }
}
