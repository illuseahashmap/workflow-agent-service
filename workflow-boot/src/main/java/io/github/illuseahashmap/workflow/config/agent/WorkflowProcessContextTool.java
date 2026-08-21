package io.github.illuseahashmap.workflow.config.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowProcessContextReader;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Composition adapter: exposes workflow context as a governed, read-only Agent tool.
 * It contains no Flowable access and cannot mutate a process.
 */
@Component
public class WorkflowProcessContextTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"processInstanceId":{"type":"string","minLength":1,"maxLength":128,"description":"仅手动测试时传入；流程运行由平台上下文注入"}}}
            """;

    private final WorkflowProcessContextReader contextReader;
    private final ObjectMapper objectMapper;

    public WorkflowProcessContextTool(WorkflowProcessContextReader contextReader, ObjectMapper objectMapper) {
        this.contextReader = contextReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "workflow_process_context";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public Result execute(Request request) {
        String processInstanceId = processInstanceId(request);
        var context = contextReader.read(request.tenantCode(), processInstanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist"));
        try {
            return new Result(objectMapper.writeValueAsString(context), request.idempotencyKey());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Unable to serialize workflow process context", exception);
        }
    }

    private String processInstanceId(Request request) {
        Object value = request.processInstanceId();
        if (value == null) {
            value = request.arguments() == null ? null : request.arguments().get("processInstanceId");
        }
        if (value == null || value.toString().trim().isEmpty() || value.toString().length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Agent tool processInstanceId must be a non-empty string");
        }
        return value.toString().trim();
    }
}
