package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessVariablePolicyTest {

    @Test
    void rejectsClientAttemptToOverrideTenantVariables() {
        assertThatThrownBy(() -> ProcessVariablePolicy.clientVariables(
                Map.of("tenantId", "another-tenant")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void enrichesAcceptedVariablesWithTrustedTenantContext() {
        Map<String, Object> variables = ProcessVariablePolicy.enrichWithTenant(
                Map.of("amount", 100),
                new TenantContext.TenantInfo("tenant-id", "tenant-code", "Tenant Name"));

        assertThat(variables).containsEntry("amount", 100)
                .containsEntry("tenantId", "tenant-id")
                .containsEntry("tenantCode", "tenant-code");
    }
}
