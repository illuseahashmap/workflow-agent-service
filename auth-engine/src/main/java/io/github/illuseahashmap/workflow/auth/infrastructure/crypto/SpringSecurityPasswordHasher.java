package io.github.illuseahashmap.workflow.auth.infrastructure.crypto;

import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class SpringSecurityPasswordHasher implements PasswordHasher {

    private final PasswordEncoder delegate;

    public SpringSecurityPasswordHasher(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public String hash(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return delegate.matches(rawPassword, passwordHash);
    }
}
