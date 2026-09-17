package io.github.illuseahashmap.agent.provider.application.port;

public interface AgentCredentialResolver {

    String resolve(String tenantCode, long providerId);
}
