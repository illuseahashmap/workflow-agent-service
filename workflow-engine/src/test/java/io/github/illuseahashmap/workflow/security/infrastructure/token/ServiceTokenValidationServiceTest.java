package io.github.illuseahashmap.workflow.security.infrastructure.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.security.application.port.ServiceClientRepository;
import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ServiceTokenValidationServiceTest {

    private ServiceTokenValidationService validationService;

    @BeforeEach
    void setUp() {
        WorkflowSecurityProperties properties = new WorkflowSecurityProperties();
        validationService = new ServiceTokenValidationService(
                mock(ServiceClientRepository.class),
                mock(JdbcTemplate.class),
                properties,
                new ObjectMapper());
    }

    @Test
    void rejectsTenantOutsideClientScope() {
        ServiceClient client = new ServiceClient("client", null, "cipher", 2,
                "tenant-a", "/workflow/**", true, null);
        ServiceTokenPayload payload = payload("tenant-b", 2);

        BusinessException exception = assertThrows(BusinessException.class, () -> validationService.validate(
                payload, client, "POST", "/workflow/process/start", "hash"));

        assertEquals("Workflow client cannot access this tenant", exception.getMessage());
    }

    @Test
    void requiresCurrentSecretVersion() {
        ServiceClient client = new ServiceClient("client", null, "cipher", 2,
                "*", "*", true, null);

        BusinessException exception = assertThrows(BusinessException.class, () -> validationService.validate(
                payload("tenant-a", 1), client, "POST", "/workflow/process/start", "hash"));

        assertEquals("Workflow token version is invalid", exception.getMessage());
    }

    private ServiceTokenPayload payload(String tenantCode, int version) {
        return new ServiceTokenPayload(
                "client", tenantCode, Instant.now().getEpochSecond(), "nonce",
                "POST", "/workflow/process/start", "hash", version);
    }
}
