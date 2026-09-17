package io.github.illuseahashmap.agent.runtime.application;

import io.github.illuseahashmap.agent.runtime.application.dto.AgentManualRunCommand;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentFlowableRunCommand;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunSubmissionView;

public interface AgentRunSubmissionService {

    AgentRunSubmissionView submitManual(AgentManualRunCommand command);

    AgentRunSubmissionView submitFlowable(AgentFlowableRunCommand command);
}
