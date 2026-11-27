package io.github.illuseahashmap.agent.runtime.interfaces.rest;

import io.github.illuseahashmap.agent.runtime.application.AgentRunSubmissionService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentManualRunCommand;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunSubmissionView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-runs/manual-tests")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:run:execute')")
public class AgentManualRunController {

    private final AgentRunSubmissionService submissionService;

    public AgentManualRunController(AgentRunSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    public ApiResponse<AgentRunSubmissionView> submit(@Valid @RequestBody AgentManualRunCommand command) {
        return ApiResponse.ok(submissionService.submitManual(command));
    }
}
