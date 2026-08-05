package io.github.illuseahashmap.workflow.auth.domain;

public interface SecurityAuditRepository {

    void record(String eventType,
                String actorId,
                String tenantCode,
                String sourceAddress,
                String subject,
                String outcome,
                String details);
}
