package io.github.illuseahashmap.workflow.assignment.application.dto;

import java.util.List;

public record AssignmentRuleInheritResult(
        String sourceProcessDefinitionId,
        Integer sourceVersion,
        String targetProcessDefinitionId,
        Integer targetVersion,
        int copiedCount,
        int skippedCount,
        List<String> skippedReasons
) {
}
