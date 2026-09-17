package io.github.illuseahashmap.workflow.config.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowProcessContextReader;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowProcessContextToolTest {

    @Test
    void returnsBoundedWorkflowContextAsToolOutput() throws Exception {
        WorkflowProcessContextReader reader = (tenant, instance) -> Optional.of(
                new WorkflowProcessContextReader.WorkflowProcessContext(
                        instance, "leave", "Leave", "LEAVE-1", "RUNNING",
                        List.of(new WorkflowProcessContextReader.ActiveTask("review", "Review", "alice")),
                        Map.of("amount", 12)));

        String output = new WorkflowProcessContextTool(reader, new ObjectMapper()).execute(
                new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request(
                        "tenant-a", Map.of("processInstanceId", "instance-1"),
                        Duration.ofSeconds(1), "trace-1")).output();

        assertThat(new ObjectMapper().readTree(output).get("processInstanceId").asText()).isEqualTo("instance-1");
        assertThat(output).contains("amount");
    }

    @Test
    void rejectsMissingProcessInstanceId() {
        WorkflowProcessContextTool tool = new WorkflowProcessContextTool(
                (tenant, instance) -> Optional.empty(), new ObjectMapper());

        assertThatThrownBy(() -> tool.execute(new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request(
                "tenant-a", Map.of(), Duration.ofSeconds(1), "trace-1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void usesRuntimeProcessInstanceContextWhenModelDoesNotProvideIt() throws Exception {
        WorkflowProcessContextReader reader = (tenant, instance) -> Optional.of(
                new WorkflowProcessContextReader.WorkflowProcessContext(
                        instance, "leave", "Leave", "LEAVE-1", "RUNNING", List.of(), Map.of()));
        WorkflowProcessContextTool tool = new WorkflowProcessContextTool(reader, new ObjectMapper());

        String output = tool.execute(new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request(
                "tenant-a", Map.of(), Duration.ofSeconds(1), "trace-1", "idem-1", "process-1")).output();

        assertThat(new ObjectMapper().readTree(output).get("processInstanceId").asText()).isEqualTo("process-1");
    }
}
