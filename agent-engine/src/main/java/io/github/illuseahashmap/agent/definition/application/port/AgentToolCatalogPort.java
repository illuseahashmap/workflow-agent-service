package io.github.illuseahashmap.agent.definition.application.port;

import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;

/** Definition-owned port for validating the immutable tool set at publication time. */
public interface AgentToolCatalogPort {

    void validatePublication(String tenantCode, AgentExecutionMode executionMode, String toolSetJson);
}
