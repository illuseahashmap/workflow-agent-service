package io.github.illuseahashmap.agent.mcp.application.port;

public interface McpCredentialResolver {
    String resolveAuthorization(String tenantCode, String credentialRef);
}
