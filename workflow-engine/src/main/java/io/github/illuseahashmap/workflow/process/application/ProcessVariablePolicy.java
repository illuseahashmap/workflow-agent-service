package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ProcessVariablePolicy {

    private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of("tenantId", "tenantCode");
    private static final Set<String> RESERVED_VARIABLE_SUFFIXES = Set.of(
            "_assignee", "_assigneeList", "_candidateGroupList");

    private ProcessVariablePolicy() {
    }

    public static Map<String, Object> clientVariables(Map<String, Object> variables) {
        Map<String, Object> safe = variables == null ? new HashMap<>() : new HashMap<>(variables);
        for (String reservedName : RESERVED_VARIABLE_NAMES) {
            if (safe.containsKey(reservedName)) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST, "Process variable is reserved by the platform: " + reservedName);
            }
        }
        safe.keySet().stream()
                .filter(ProcessVariablePolicy::isParticipantVariable)
                .findFirst()
                .ifPresent(variableName -> {
                    throw new BusinessException(
                            ErrorCode.BAD_REQUEST,
                            "Participant variables must use participantAssignments: " + variableName);
                });
        return safe;
    }

    public static Map<String, Object> enrichWithTenant(Map<String, Object> variables,
                                                       TenantContext.TenantInfo tenant) {
        return enrichTrustedWithTenant(clientVariables(variables), tenant);
    }

    public static Map<String, Object> enrichTrustedWithTenant(Map<String, Object> trustedVariables,
                                                              TenantContext.TenantInfo tenant) {
        Map<String, Object> enriched = trustedVariables == null
                ? new HashMap<>() : new HashMap<>(trustedVariables);
        enriched.put("tenantId", tenant.tenantId());
        enriched.put("tenantCode", tenant.tenantCode());
        return enriched;
    }

    private static boolean isParticipantVariable(String variableName) {
        return RESERVED_VARIABLE_SUFFIXES.stream().anyMatch(variableName::endsWith);
    }

    public static boolean isInternalVariable(String variableName) {
        return RESERVED_VARIABLE_NAMES.contains(variableName) || isParticipantVariable(variableName);
    }
}
