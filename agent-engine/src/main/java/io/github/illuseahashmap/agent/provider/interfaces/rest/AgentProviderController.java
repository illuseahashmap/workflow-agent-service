package io.github.illuseahashmap.agent.provider.interfaces.rest;

import io.github.illuseahashmap.agent.provider.application.AgentProviderService;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderCommand;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-providers")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:manage')")
public class AgentProviderController {

    private final AgentProviderService service;

    public AgentProviderController(AgentProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AgentProviderView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled
    ) {
        return ApiResponse.ok(service.page(pageNum, pageSize, keyword, enabled));
    }

    @GetMapping("/enabled")
    public ApiResponse<List<AgentProviderView>> enabled() {
        return ApiResponse.ok(service.enabled());
    }

    @PostMapping
    public ApiResponse<AgentProviderView> create(@Valid @RequestBody AgentProviderCommand command) {
        return ApiResponse.ok(service.create(command));
    }

    @PostMapping("/{id}")
    public ApiResponse<AgentProviderView> update(
            @PathVariable long id,
            @Valid @RequestBody AgentProviderCommand command
    ) {
        return ApiResponse.ok(service.update(id, command));
    }
}
