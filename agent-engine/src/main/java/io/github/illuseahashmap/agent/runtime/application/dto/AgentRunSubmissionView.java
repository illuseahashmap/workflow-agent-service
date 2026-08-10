package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;

public record AgentRunSubmissionView(long runId, AgentRunStatus status) {
}
