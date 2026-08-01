package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ProcessVariablePolicy {

    private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of("tenantId", "tenantCode");

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
        return safe;
    }

    public static Map<String, Object> enrichWithTenant(Map<String, Object> variables,
                                                       TenantContext.TenantInfo tenant) {
        Map<String, Object> enriched = new HashMap<>(clientVariables(variables));
        enriched.put("tenantId", tenant.tenantId());
        enriched.put("tenantCode", tenant.tenantCode());
        return enriched;
    }
}
