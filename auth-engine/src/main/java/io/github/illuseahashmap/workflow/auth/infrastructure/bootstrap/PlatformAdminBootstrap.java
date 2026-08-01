package io.github.illuseahashmap.workflow.auth.infrastructure.bootstrap;

import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "workflow.auth.bootstrap-admin", name = "enabled", havingValue = "true")
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdminBootstrap.class);
    private static final String DEFAULT_TENANT_CODE = "default";

    private final PlatformAdminBootstrapProperties properties;
    private final AuthUserRepository userRepository;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthAuthorizationRepository authorizationRepository;
    private final PasswordHasher passwordHasher;

    public PlatformAdminBootstrap(PlatformAdminBootstrapProperties properties,
                                  AuthUserRepository userRepository,
                                  AuthTenantRepository tenantRepository,
                                  AuthMembershipRepository membershipRepository,
                                  AuthAuthorizationRepository authorizationRepository,
                                  PasswordHasher passwordHasher) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationRepository = authorizationRepository;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String username = normalizedUsername();
        AuthUser user = userRepository.findByUsername(username).orElseGet(() -> createUser(username));
        membershipRepository.add(user.userId(), DEFAULT_TENANT_CODE);
        authorizationRepository.grantRole(user.userId(), DEFAULT_TENANT_CODE, "USER");
        authorizationRepository.grantRole(user.userId(), "*", "PLATFORM_ADMIN");
        LOGGER.info("Platform administrator bootstrap applied: username={}", username);
    }

    private AuthUser createUser(String username) {
        if (!StringUtils.hasText(properties.getPassword()) || properties.getPassword().length() < 12) {
            throw new IllegalStateException("Bootstrap administrator password must contain at least 12 characters");
        }
        AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(DEFAULT_TENANT_CODE)
                .filter(AuthTenantRepository.AuthTenant::enabled)
                .orElseThrow(() -> new IllegalStateException("Default tenant is missing or disabled"));
        String displayName = StringUtils.hasText(properties.getDisplayName())
                ? properties.getDisplayName().trim()
                : username;
        return userRepository.save(UUID.randomUUID().toString(), username, displayName,
                passwordHasher.hash(properties.getPassword()), tenant.tenantCode());
    }

    private String normalizedUsername() {
        if (!StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("Bootstrap administrator username is required");
        }
        return properties.getUsername().trim().toLowerCase(java.util.Locale.ROOT);
    }
}
