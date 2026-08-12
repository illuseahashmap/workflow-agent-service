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
        requireChanged(eventStore.replay(eventId));
        audit("AGENT_COMPLETION_REPLAYED", eventId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void ignore(UUID eventId) {
        requireChanged(eventStore.ignore(eventId));
        audit("AGENT_COMPLETION_IGNORED", eventId);
    }

    private void requireChanged(boolean changed) {
        if (!changed) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Dead-letter completion event was not found");
        }
    }

    private void audit(String eventType, UUID eventId) {
        var principal = principalProvider.current();
        auditRepository.record(
                eventType, principal.principalId(), principal.tenantCode(), null,
                eventId.toString(), "SUCCESS", "Agent completion dead-letter operation");
    }
}
