package io.github.illuseahashmap.agent.runtime.application;

public interface AgentRunOperationsService {

    void retryFailed(long runId, String reason, int retryWindowSeconds);

    void cancelActive(long runId, String reason);

    void pauseActive(long runId, String reason);

    void resumePaused(long runId, String reason, int resumeWindowSeconds);
}
