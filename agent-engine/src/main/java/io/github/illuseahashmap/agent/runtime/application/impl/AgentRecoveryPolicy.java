package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentRecoveryAction;
import org.springframework.stereotype.Component;

/** Selects a bounded recovery action; it never repeats an operation by itself. */
@Component
public class AgentRecoveryPolicy {

    public Decision decide(AgentFailure failure, boolean retryScheduled) {
        AgentFailureCategory category = failure.category();
        if (retryScheduled) {
            return new Decision(category, AgentRecoveryAction.RETRY_PROVIDER, false,
                    appendDetail("临时 Provider 故障，已按退避策略安排新的 Attempt", failure));
        }
        return switch (category) {
            case OUTPUT_CONTRACT -> new Decision(category, AgentRecoveryAction.WAIT_FOR_REVIEW, true,
                    appendDetail("输出修复未通过契约校验，需要检查输出 Schema 或人工处理", failure));
            case TOOL_PROTOCOL -> new Decision(category, AgentRecoveryAction.REPAIR_TOOL_CALL, true,
                    appendDetail("工具调用协议修复未成功，需要检查工具定义或模型输出", failure));
            case INPUT_CONTRACT, CONFIGURATION, PROVIDER_PERMANENT ->
                    new Decision(category, AgentRecoveryAction.FIX_CONFIGURATION,
                            true, appendDetail("输入、Provider 或 Agent 配置不满足执行契约，需要修正配置", failure));
            case RESULT_POLICY, BUSINESS_REJECTION -> new Decision(category, AgentRecoveryAction.REJECT_BUSINESS, true,
                    appendDetail("业务结果被策略拒绝，不应通过重复模型调用绕过", failure));
            case PROVIDER_TRANSIENT -> new Decision(category, AgentRecoveryAction.WAIT_FOR_REVIEW, true,
                    appendDetail("Provider 临时故障已达到重试预算，需要人工处理或切换 Provider", failure));
            case DEADLINE -> new Decision(category, AgentRecoveryAction.TERMINATE, false,
                    appendDetail("运行已超过截止时间", failure));
            case EXECUTION_UNEXPECTED -> new Decision(category, AgentRecoveryAction.WAIT_FOR_REVIEW, true,
                    appendDetail("执行异常未能安全分类，需要管理员结合 Trace ID 排查", failure));
        };
    }

    private String appendDetail(String message, AgentFailure failure) {
        String detail = failure.safeMessage();
        if (detail == null || detail.isBlank() || detail.equals("Agent execution failed")) {
            return message;
        }
        return (message + "：" + detail).substring(0, Math.min(512, (message + "：" + detail).length()));
    }

    public record Decision(
            AgentFailureCategory failureCategory,
            AgentRecoveryAction action,
            boolean requiresHumanReview,
            String reason
    ) {
    }
}
