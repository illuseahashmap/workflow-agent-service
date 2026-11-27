package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Resolves a credential reference from deployment configuration, never from API payloads. */
@Component
public class EnvironmentMcpCredentialResolver implements McpCredentialResolver {

    private final Environment environment;

    public EnvironmentMcpCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolveAuthorization(String tenantCode, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        return environment.getProperty("mcp.credentials." + tenantCode + "." + credentialRef);
    }
}
