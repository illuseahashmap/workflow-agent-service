package io.github.illuseahashmap.workflow.process.application;

/** Stable process-variable contract emitted when a workflow Agent activity completes. */
public final class AgentWorkflowVariables {

    public static final String RUN_ID = "agentRunId";
    public static final String RUN_STATUS = "agentRunStatus";
    public static final String RESULT_STATUS = "agentResultStatus";
    public static final String ERROR_CODE = "agentRunErrorCode";
    public static final String REVIEW_REQUIRED = "agentReviewRequired";

    private AgentWorkflowVariables() {
    }
}
