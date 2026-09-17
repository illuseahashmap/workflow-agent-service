package io.github.illuseahashmap.workflow.auth.infrastructure.config;

import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Prevents an insecure authentication cookie configuration outside local development. */
@Component
public class AuthCookieSecurityValidator {

    private final AuthTokenProperties tokenProperties;
    private final String environment;

    public AuthCookieSecurityValidator(AuthTokenProperties tokenProperties,
                                       @Value("${workflow.runtime.environment:production}") String environment) {
        this.tokenProperties = tokenProperties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!tokenProperties.isCookieSecure() && !"development".equalsIgnoreCase(environment)
                && !"test".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "workflow.auth.token.cookie-secure=false is only allowed in development or test environments");
        }
    }
}
