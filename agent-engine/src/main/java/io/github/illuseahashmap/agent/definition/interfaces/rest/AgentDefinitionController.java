package io.github.illuseahashmap.agent.definition.interfaces.rest;

import io.github.illuseahashmap.agent.definition.application.AgentDefinitionService;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionView;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionView;
import io.github.illuseahashmap.agent.definition.application.dto.PublishedAgentVersionView;
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
@RequestMapping("/agents")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('agent:manage')")
public class AgentDefinitionController {

    private final AgentDefinitionService service;

    public AgentDefinitionController(AgentDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AgentDefinitionView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled
    ) {
        return ApiResponse.ok(service.page(pageNum, pageSize, keyword, enabled));
    }

    @GetMapping("/published-versions")
    public ApiResponse<PageResult<PublishedAgentVersionView>> publishedVersions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long versionId
    ) {
        return ApiResponse.ok(service.publishedVersions(pageNum, pageSize, keyword, versionId));
    }

    @PostMapping
    public ApiResponse<AgentDefinitionView> create(@Valid @RequestBody AgentDefinitionCommand command) {
        return ApiResponse.ok(service.create(command));
    }

    @PostMapping("/{id}")
    public ApiResponse<AgentDefinitionView> update(
            @PathVariable long id,
            @Valid @RequestBody AgentDefinitionCommand command
    ) {
        return ApiResponse.ok(service.update(id, command));
    }

    @GetMapping("/{definitionId}/versions")
    public ApiResponse<List<AgentVersionView>> versions(@PathVariable long definitionId) {
        return ApiResponse.ok(service.versions(definitionId));
    }

    @PostMapping("/{definitionId}/versions")
    public ApiResponse<AgentVersionView> createDraft(@PathVariable long definitionId) {
        return ApiResponse.ok(service.createDraft(definitionId));
    }

    @PostMapping("/{definitionId}/versions/{versionId}")
    public ApiResponse<AgentVersionView> updateDraft(
            @PathVariable long definitionId,
            @PathVariable long versionId,
            @Valid @RequestBody AgentVersionCommand command
    ) {
        return ApiResponse.ok(service.updateDraft(definitionId, versionId, command));
    }

    @PostMapping("/{definitionId}/versions/{versionId}/publish")
    public ApiResponse<AgentVersionView> publish(
            @PathVariable long definitionId,
            @PathVariable long versionId
    ) {
        return ApiResponse.ok(service.publish(definitionId, versionId));
    }
}
