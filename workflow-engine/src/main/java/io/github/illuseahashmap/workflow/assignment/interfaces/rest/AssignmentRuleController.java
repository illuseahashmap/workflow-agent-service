package io.github.illuseahashmap.workflow.assignment.interfaces.rest;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow/node-assignment-rule")
public class AssignmentRuleController {

    private final AssignmentRuleService assignmentRuleService;

    public AssignmentRuleController(AssignmentRuleService assignmentRuleService) {
        this.assignmentRuleService = assignmentRuleService;
    }

    @GetMapping
    public ApiResponse<PageResult<NodeAssignmentRule>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionId,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String taskDefinitionKey,
            @RequestParam(required = false) String variableName,
            @RequestParam(required = false) AssignmentType assignmentType,
            @RequestParam(required = false) EmptyUserStrategy emptyUserStrategy) {
        return ApiResponse.ok(assignmentRuleService.page(
                pageNum, pageSize, processDefinitionKey, processDefinitionId, version,
                taskDefinitionKey, variableName, assignmentType, emptyUserStrategy));
    }

    @PostMapping
    public ApiResponse<NodeAssignmentRule> create(@Valid @RequestBody AssignmentRuleCommand command) {
        return ApiResponse.ok(assignmentRuleService.create(command));
    }

    @PostMapping("/inherit")
    public ApiResponse<AssignmentRuleInheritResult> inherit(
            @Valid @RequestBody AssignmentRuleInheritCommand command) {
        return ApiResponse.ok(assignmentRuleService.inherit(command.processDefinitionId()));
    }

    @PostMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id,
                                    @Valid @RequestBody AssignmentRuleCommand command) {
        assignmentRuleService.update(id, command);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        assignmentRuleService.delete(id);
        return ApiResponse.ok();
    }
}
