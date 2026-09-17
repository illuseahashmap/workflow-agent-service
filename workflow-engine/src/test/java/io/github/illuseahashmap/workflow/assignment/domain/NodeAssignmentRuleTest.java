package io.github.illuseahashmap.workflow.assignment.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NodeAssignmentRuleTest {

    @Test
    void domainRejectsTargetsThatDoNotMatchAssignmentType() {
        NodeAssignmentRule rule = new NodeAssignmentRule(
                null, "tenant-a", "definition:1", "definition", 1, "approve", 100,
                AssignmentType.ASSIGNEE, null, true, null, List.of(),
                List.of(new AssignmentTarget(null, AssignmentTargetType.CANDIDATE_USER, "alice", 10)),
                null, null);

        assertThatThrownBy(rule::validate)
                .isInstanceOfSatisfying(
                        io.github.illuseahashmap.workflow.shared.exception.BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                                .contains("Assignment targets do not match"));
    }

    @Test
    void domainRequiresFallbackWhenEmptyUserStrategyTargetsAssignee() {
        NodeAssignmentRule rule = new NodeAssignmentRule(
                null, "tenant-a", "definition:1", "definition", 1, "approve", 100,
                AssignmentType.ASSIGNEE, EmptyUserStrategy.TO_ASSIGNEE, true, null, List.of(),
                List.of(new AssignmentTarget(null, AssignmentTargetType.ASSIGNEE, "alice", 10)),
                null, null);

        assertThatThrownBy(rule::validate)
                .isInstanceOf(io.github.illuseahashmap.workflow.shared.exception.BusinessException.class)
                .hasMessageContaining("fallback assignee");
    }
}
