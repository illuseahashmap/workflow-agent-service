package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunPayloadView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunQueryRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunQueryServiceImplTest {

    private final AgentRunQueryRepository repository = mock(AgentRunQueryRepository.class);
    private final TenantProvider tenantProvider = mock(TenantProvider.class);
    private final AgentRunQueryServiceImpl service = new AgentRunQueryServiceImpl(repository, tenantProvider);

    @BeforeEach
    void setUpTenant() {
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo(
                "tenant-id", "tenant-a", "Tenant A"));
    }

    @Test
    void loadsRunDetailInsideCurrentTenant() {
        AgentRunDetailView detail = new AgentRunDetailView(
                runView(), new AgentRunPayloadView(null, null),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(repository.findDetail("tenant-a", 10L)).thenReturn(Optional.of(detail));

        AgentRunDetailView result = service.detail(10L);

        assertThat(result).isSameAs(detail);
        verify(repository).findDetail("tenant-a", 10L);
    }

    @Test
    void returnsNotFoundWithoutLeakingAnotherTenantRun() {
        when(repository.findDetail("tenant-a", 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent run does not exist");
    }

    @Test
    void rejectsNonPositiveRunId() {
        assertThatThrownBy(() -> service.detail(0L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent run id must be positive");
    }

    private AgentRunView runView() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-08T10:00:00+08:00");
        return new AgentRunView(
                10L,
                "review_agent",
                "Review Agent",
                1,
                AgentRunStatus.QUEUED,
                null,
                null,
                null,
                null,
                now.plusMinutes(2),
                null,
                null,
                now,
                now);
    }
}
