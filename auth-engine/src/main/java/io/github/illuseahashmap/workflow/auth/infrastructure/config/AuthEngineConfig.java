package io.github.illuseahashmap.workflow.auth.infrastructure.config;

import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import io.github.illuseahashmap.workflow.auth.infrastructure.crypto.SpringSecurityPasswordHasher;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import io.github.illuseahashmap.workflow.auth.infrastructure.bootstrap.PlatformAdminBootstrapProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties({
        AuthTokenProperties.class,
        PlatformAdminBootstrapProperties.class,
        SelfRegistrationProperties.class,
        AuthenticationProtectionProperties.class
})
public class AuthEngineConfig {

    @Bean
    public PasswordHasher passwordHasher() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        return new SpringSecurityPasswordHasher(encoder);
    }
}
