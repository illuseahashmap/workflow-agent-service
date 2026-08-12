package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.auth.domain.SecurityAuditRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional use cases for restricted dead-letter recovery operations. */
@Service
public class AgentCompletionOperationsService {

    private final AgentCompletionEventStore eventStore;
    private final SecurityAuditRepository auditRepository;
    private final CurrentPrincipalProvider principalProvider;

    public AgentCompletionOperationsService(
            AgentCompletionEventStore eventStore,
            SecurityAuditRepository auditRepository,
            CurrentPrincipalProvider principalProvider
    ) {
        this.eventStore = eventStore;
        this.auditRepository = auditRepository;
        this.principalProvider = principalProvider;
    }

    public List<AgentCompletionEventStore.DeadLetterEvent> deadLetters(int limit) {
        return eventStore.deadLetters(limit);
    }

    @Transactional(rollbackFor = Exception.class)
    public void replay(UUID eventId) {
        var event = requireFound(eventStore.replay(eventId));
        audit("AGENT_COMPLETION_REPLAYED", event, "REPLAY", "Retry requested by operator");
    }

    @Transactional(rollbackFor = Exception.class)
    public void ignore(UUID eventId, String reason) {
        var principal = principalProvider.current();
        var event = requireFound(eventStore.ignore(eventId, reason.strip(), principal.principalId()));
        audit("AGENT_COMPLETION_IGNORED", event, "IGNORE", reason.strip());
    }

    private AgentCompletionEventStore.DeadLetterEvent requireFound(
            AgentCompletionEventStore.DeadLetterEvent event) {
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Dead-letter completion event was not found");
        }
        return event;
    }

    private void audit(
            String eventType,
            AgentCompletionEventStore.DeadLetterEvent event,
            String method,
            String reason
    ) {
        var principal = principalProvider.current();
        auditRepository.record(
                eventType, principal.principalId(), event.tenantCode(), null,
                event.eventId().toString(), "SUCCESS",
                "method=" + method + "; reason=" + reason
                        + "; targetTenant=" + event.tenantCode()
                        + "; agentRun=" + event.aggregateId()
                        + "; processInstance=" + event.processInstanceId()
                        + "; execution=" + event.executionId()
                        + "; originalError=" + event.lastError());
    }
}
