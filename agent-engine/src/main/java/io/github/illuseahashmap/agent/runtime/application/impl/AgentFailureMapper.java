package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

/** Converts boundary-specific failures into the runtime's stable failure model. */
@Component
public class AgentFailureMapper {

    public AgentFailure from(AgentExecutionException exception) {
        return exception.failure();
    }

    public AgentFailure fromProvider(ModelProviderException exception) {
        AgentFailureCategory category = switch (exception.failureKind()) {
            case RETRYABLE -> AgentFailureCategory.PROVIDER_TRANSIENT;
            case TIMEOUT -> "AGENT_DEADLINE_EXCEEDED".equals(exception.errorCode())
                    ? AgentFailureCategory.DEADLINE : AgentFailureCategory.PROVIDER_TRANSIENT;
            case PERMANENT -> AgentFailureCategory.PROVIDER_PERMANENT;
        };
        return new AgentFailure(
                exception.errorCode(), category,
                exception.failureKind() != ModelProviderFailureKind.PERMANENT,
                ResultStatus.FAILED, safeMessage(exception));
    }

    public AgentFailure fromMcp(McpClientException exception) {
        AgentFailureCategory category = switch (exception.failureKind()) {
            case TIMEOUT -> AgentFailureCategory.DEADLINE;
            case UNAVAILABLE, RATE_LIMITED -> AgentFailureCategory.PROVIDER_TRANSIENT;
            case AUTHENTICATION, PROTOCOL_ERROR, TOOL_ERROR -> AgentFailureCategory.TOOL_PROTOCOL;
        };
        return new AgentFailure(exception.errorCode(), category, exception.retryable(),
                ResultStatus.FAILED, safeMcpMessage(exception.failureKind()));
    }

    public AgentFailure configuration(BusinessException exception) {
        return new AgentFailure(
                "AGENT_CONFIGURATION_ERROR", AgentFailureCategory.CONFIGURATION,
                false, ResultStatus.FAILED, "Agent 配置不满足执行契约");
    }

    public AgentFailure unexpected(Throwable exception) {
        return new AgentFailure(
                "AGENT_EXECUTION_ERROR", AgentFailureCategory.EXECUTION_UNEXPECTED,
                false, ResultStatus.FAILED, "Agent 执行出现未分类异常");
    }

    private String safeMessage(ModelProviderException exception) {
        if (exception.safeDetail() != null && !exception.safeDetail().isBlank()) {
            return exception.safeDetail();
        }
        return switch (exception.failureKind()) {
            case RETRYABLE -> "模型服务暂时不可用，系统将按策略重试";
            case TIMEOUT -> "模型服务调用超时";
            case PERMANENT -> "模型服务拒绝或返回了不可识别的响应";
        };
    }

    private String safeMcpMessage(McpFailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> "MCP 服务调用超时，系统将按策略重试";
            case UNAVAILABLE -> "MCP 服务暂时不可用，系统将按策略重试";
            case RATE_LIMITED -> "MCP 服务触发限流，系统将按策略重试";
            case AUTHENTICATION -> "MCP 服务认证失败，请修复连接凭据";
            case PROTOCOL_ERROR -> "MCP 服务协议或目录不满足执行契约";
            case TOOL_ERROR -> "MCP 工具返回了业务错误";
        };
    }
}
