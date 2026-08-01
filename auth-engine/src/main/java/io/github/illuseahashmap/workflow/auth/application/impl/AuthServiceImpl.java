package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_TENANT_CODE = "default";
    private static final String DEFAULT_ROLE_CODE = "USER";

    private final AuthUserRepository userRepository;
    private final AuthTenantRepository tenantRepository;
    private final AuthAuthorizationRepository authorizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokenService;

    public AuthServiceImpl(AuthUserRepository userRepository,
                           AuthTenantRepository tenantRepository,
                           AuthAuthorizationRepository authorizationRepository,
                           PasswordEncoder passwordEncoder,
                           AuthTokenService tokenService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.authorizationRepository = authorizationRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Override
    @Transactional
    public AuthTokenResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String tenantCode = normalizeTenantCode(request.tenantCode());
        String displayName = StringUtils.hasText(request.displayName()) ? request.displayName().trim() : username;

        AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Tenant does not exist"));
        if (!tenant.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant is disabled");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Username already exists");
        }

        AuthUser user = userRepository.save(
                UUID.randomUUID().toString(),
                username,
                displayName,
                passwordEncoder.encode(request.password()),
                tenantCode
        );
        authorizationRepository.grantRole(user.userId(), tenantCode, DEFAULT_ROLE_CODE);
        return tokenService.issue(user, Set.of(DEFAULT_ROLE_CODE), Set.of());
    }

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        String username = normalizeUsername(request.username());
        AuthUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password"));
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "User is disabled");
        }
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
        }
        Set<String> roles = authorizationRepository.findRoleCodes(user.userId(), user.tenantCode());
        if (roles.isEmpty()) {
            roles = Set.of(DEFAULT_ROLE_CODE);
        }
        Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), user.tenantCode());
        return tokenService.issue(user, roles, permissions);
    }

    @Override
    public CurrentUserResponse currentUser() {
        CurrentPrincipal principal = CurrentPrincipalContext.current();
        return new CurrentUserResponse(
                principal.principalId(),
                principal.username(),
                principal.displayName(),
                principal.tenantCode(),
                principal.roles(),
                principal.permissions()
        );
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Username is required");
        }
        return username.trim().toLowerCase();
    }

    private String normalizeTenantCode(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            return DEFAULT_TENANT_CODE;
        }
        return tenantCode.trim();
    }
}
