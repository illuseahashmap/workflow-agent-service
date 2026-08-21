package io.github.illuseahashmap.agent.runtime.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunQueryRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only platform tool for correlating an Agent with a tenant-owned run. */
@Component
public class AgentRunStatusTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","required":["runId"],"properties":{"runId":{"type":"integer"}}}
            """;

    private final AgentRunQueryRepository queryRepository;
    private final ObjectMapper objectMapper;

    public AgentRunStatusTool(AgentRunQueryRepository queryRepository, ObjectMapper objectMapper) {
        this.queryRepository = queryRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "agent_run_status";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public Result execute(Request request) {
        long runId = runId(request.arguments());
        var detail = queryRepository.findDetail(request.tenantCode(), runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent run does not exist"));
        try {
            String output = objectMapper.writeValueAsString(Map.of(
                    "runId", detail.run().id(),
                    "agentCode", detail.run().agentCode(),
                    "status", detail.run().status().name(),
                    "resultStatus", detail.run().resultStatus() == null
                            ? "" : detail.run().resultStatus().name(),
                    "errorCode", detail.run().errorCode() == null ? "" : detail.run().errorCode()));
            return new Result(output, request.idempotencyKey());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to serialize Agent tool result", exception);
        }
    }

    private long runId(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("runId");
        try {
            long parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (parsed > 0) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Report a stable contract error below.
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent tool runId must be positive");
    }
}
