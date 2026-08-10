package io.github.illuseahashmap.agent.runtime.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.runtime.application.AgentRunSubmissionService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentManualRunCommand;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunSubmissionView;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTriggerType;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunSubmissionServiceImpl implements AgentRunSubmissionService {

    private final AgentDefinitionRepository definitionRepository;
    private final AgentDefinitionVersionRepository versionRepository;
    private final AgentProviderRepository providerRepository;
    private final AgentRunExecutionRepository executionRepository;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;
    private final ObjectMapper objectMapper;

    public AgentRunSubmissionServiceImpl(
            AgentDefinitionRepository definitionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            AgentRunExecutionRepository executionRepository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider,
            ObjectMapper objectMapper
    ) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.providerRepository = providerRepository;
        this.executionRepository = executionRepository;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRunSubmissionView submitManual(AgentManualRunCommand command) {
        String tenantCode = tenantProvider.current().tenantCode();
        AgentDefinition definition = definitionRepository.findById(tenantCode, command.definitionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent definition does not exist"));
        if (!definition.enabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Agent definition is disabled");
        }
        AgentDefinitionVersion version = versionRepository.findPublished(tenantCode, definition.id())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, "Publish an Agent version before starting a test run"));
        AgentProvider provider = providerRepository.findById(tenantCode, version.providerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent Provider does not exist"));
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Agent Provider is disabled");
        }

        Instant now = Instant.now();
        AgentRun run = executionRepository.insertQueued(new AgentRunExecutionRepository.Submission(
                tenantCode,
                version.id(),
                "manual-test:" + UUID.randomUUID(),
                AgentRunTriggerType.MANUAL_TEST,
                inputSnapshot(command.input().trim()),
                principalProvider.current().principalId(),
                now.plusSeconds(version.timeoutSeconds()),
                now));
        return new AgentRunSubmissionView(run.id(), run.status());
    }

    private String inputSnapshot(String input) {
        try {
            return objectMapper.writeValueAsString(Map.of("input", input));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to serialize Agent input", exception);
        }
    }
}
