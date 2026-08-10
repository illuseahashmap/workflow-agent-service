package io.github.illuseahashmap.agent.runtime.interfaces.rest;

import io.github.illuseahashmap.agent.runtime.application.AgentRunQueryService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-runs")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:run:read')")
public class AgentRunController {

    private final AgentRunQueryService service;

    public AgentRunController(AgentRunQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AgentRunView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.page(pageNum, pageSize, keyword, status));
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunDetailView> detail(@PathVariable long runId) {
        return ApiResponse.ok(service.detail(runId));
    }
}
