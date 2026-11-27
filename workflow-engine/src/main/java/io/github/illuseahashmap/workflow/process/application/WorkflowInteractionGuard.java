package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.InteractionDataFieldView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskInteractionRequest;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Enforces the generated Agent input contract at every workflow command boundary. */
@Component
public class WorkflowInteractionGuard {

    private final WorkflowInteractionService interactionService;

    public WorkflowInteractionGuard(WorkflowInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    public void validateStart(String processDefinitionKey,
                              String processDefinitionId,
                              Map<String, Object> variables) {
        validate(interactionService.startInteraction(new ProcessInteractionRequest(
                processDefinitionKey, processDefinitionId, variables)));
    }

    public void validateTask(String taskId, Map<String, Object> variables) {
        validate(interactionService.taskInteraction(new TaskInteractionRequest(taskId, variables)));
    }

    private void validate(ProcessInteractionView interaction) {
        interaction.fields().stream()
                .filter(InteractionDataFieldView::required)
                .filter(field -> missing(field.currentValue()))
                .findFirst()
                .ifPresent(field -> {
                    throw new BusinessException(
                            ErrorCode.BAD_REQUEST,
                            "Required Agent input is missing: " + field.label());
                });
    }

    private boolean missing(Object value) {
        return value == null || value instanceof String text && !StringUtils.hasText(text);
    }
}
