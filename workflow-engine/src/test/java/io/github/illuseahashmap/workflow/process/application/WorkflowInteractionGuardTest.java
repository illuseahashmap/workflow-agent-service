package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.dto.InteractionDataFieldView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionView;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowInteractionGuardTest {

    @Test
    void rejectsMissingRequiredAgentInputBeforeStartingProcess() {
        WorkflowInteractionService service = mock(WorkflowInteractionService.class);
        when(service.startInteraction(new ProcessInteractionRequest("order", "definition-1", Map.of())))
                .thenReturn(new ProcessInteractionView(List.of(new InteractionDataFieldView(
                        "order.customer", "Customer", null, "string", null, true, null,
                        "agent-review", "Agent review", "customer")), List.of("agent-review")));

        WorkflowInteractionGuard guard = new WorkflowInteractionGuard(service);

        assertThatThrownBy(() -> guard.validateStart("order", "definition-1", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Required Agent input is missing: Customer");
    }
}
