package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunPayloadView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunQueryRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunStatusToolTest {

    @Test
    void returnsOnlyTenantOwnedRunSummary() throws Exception {
        AgentRunQueryRepository repository = mock(AgentRunQueryRepository.class);
        when(repository.findDetail("tenant-a", 10L)).thenReturn(Optional.of(new AgentRunDetailView(
                new AgentRunView(10L, "review", "Review", 1, AgentRunStatus.SUCCEEDED,
                        null, "process-1", "agent", null, null, null, null,
                        OffsetDateTime.now(), OffsetDateTime.now()),
                new AgentRunPayloadView("secret-input", "secret-output"),
                List.of(), List.of(), List.of(), List.of(), List.of())));
        AgentRunStatusTool tool = new AgentRunStatusTool(repository, new ObjectMapper());

        String output = tool.execute(new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request(
                "tenant-a", Map.of("runId", 10), java.time.Duration.ofSeconds(1), "trace", "key")).output();

        assertThat(new ObjectMapper().readTree(output).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(output).doesNotContain("secret-input").doesNotContain("secret-output");
    }
}
