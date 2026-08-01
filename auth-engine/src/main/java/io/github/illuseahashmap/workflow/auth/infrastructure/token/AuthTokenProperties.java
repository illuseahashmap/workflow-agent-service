package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "workflow.auth.token")
@Validated
public class AuthTokenProperties {

    @NotBlank
    private String issuer = "workflow-agent-service";

    @NotBlank
    @Size(min = 32)
    private String secret;

    @Min(300)
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
