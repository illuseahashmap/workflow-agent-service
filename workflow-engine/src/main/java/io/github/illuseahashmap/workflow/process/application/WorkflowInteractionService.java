package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskInteractionRequest;

/** Generates tenant-facing data contracts without exposing BPMN variables or Agent JSON mappings. */
public interface WorkflowInteractionService {

    ProcessInteractionView startInteraction(ProcessInteractionRequest request);

    ProcessInteractionView taskInteraction(TaskInteractionRequest request);
}
