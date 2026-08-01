package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.auth.token")
public class AuthTokenProperties {

    private String issuer = "workflow-agent-service";
    private String secret = "local-dev-auth-token-secret-change-me";
    private long ttlSeconds = 7200;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
