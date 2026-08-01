package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.port.AuthTokenIssuer;
import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_TENANT_CODE = "default";
    private static final String DEFAULT_ROLE_CODE = "USER";

    private final AuthUserRepository userRepository;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthAuthorizationRepository authorizationRepository;
    private final PasswordHasher passwordHasher;
    private final AuthTokenIssuer tokenIssuer;
    private final CurrentPrincipalProvider principalProvider;

    public AuthServiceImpl(AuthUserRepository userRepository,
                           AuthTenantRepository tenantRepository,
                           AuthMembershipRepository membershipRepository,
                           AuthAuthorizationRepository authorizationRepository,
                           PasswordHasher passwordHasher,
                           AuthTokenIssuer tokenIssuer,
                           CurrentPrincipalProvider principalProvider) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationRepository = authorizationRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional
    public AuthTokenResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String tenantCode = DEFAULT_TENANT_CODE;
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
                passwordHasher.hash(request.password()),
                tenantCode
        );
        membershipRepository.add(user.userId(), tenantCode);
        authorizationRepository.grantRole(user.userId(), tenantCode, DEFAULT_ROLE_CODE);
        Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), tenantCode);
        return tokenIssuer.issue(user, tenantCode, Set.of(DEFAULT_ROLE_CODE), permissions);
    }

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        String username = normalizeUsername(request.username());
        AuthUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password"));
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "User is disabled");
        }
        if (!passwordHasher.matches(request.password(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
        }
        AuthMembershipRepository.TenantMembership membership = selectLoginMembership(user);
        String tenantCode = membership.tenantCode();
        Set<String> roles = authorizationRepository.findRoleCodes(user.userId(), tenantCode);
        if (roles.isEmpty()) {
            roles = Set.of(DEFAULT_ROLE_CODE);
        }
        Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), tenantCode);
        return tokenIssuer.issue(user, tenantCode, roles, permissions);
    }

    @Override
    public CurrentUserResponse currentUser() {
        CurrentPrincipal principal = principalProvider.current();
        return new CurrentUserResponse(
                principal.principalId(),
                principal.username(),
                principal.displayName(),
                principal.tenantCode(),
                principal.roles(),
                principal.permissions()
        );
    }

    @Override
    public List<TenantOptionResponse> tenants() {
        CurrentPrincipal principal = principalProvider.current();
        return membershipRepository.findByUserId(principal.principalId()).stream()
                .map(membership -> new TenantOptionResponse(
                        membership.tenantId(), membership.tenantCode(), membership.tenantName(),
                        membership.membershipEnabled() && membership.tenantEnabled(),
                        membership.tenantCode().equals(principal.tenantCode()),
                        authorizationRepository.findRoleCodes(principal.principalId(), membership.tenantCode())))
                .toList();
    }

    @Override
    public AuthTokenResponse switchTenant(SwitchTenantRequest request) {
        CurrentPrincipal principal = principalProvider.current();
        String tenantCode = normalizeTenantCode(request.tenantCode());
        AuthMembershipRepository.TenantMembership membership = membershipRepository
                .find(principal.principalId(), tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "User does not belong to the tenant"));
        requireEnabledMembership(membership);
        AuthUser user = userRepository.findByUserId(principal.principalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "User does not exist"));
        Set<String> roles = authorizationRepository.findRoleCodes(user.userId(), tenantCode);
        Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), tenantCode);
        return tokenIssuer.issue(user, tenantCode, roles, permissions);
    }

    private AuthMembershipRepository.TenantMembership selectLoginMembership(AuthUser user) {
        List<AuthMembershipRepository.TenantMembership> memberships = membershipRepository.findByUserId(user.userId());
        return memberships.stream()
                .filter(this::isEnabledMembership)
                .sorted((left, right) -> Boolean.compare(
                        right.tenantCode().equals(user.tenantCode()), left.tenantCode().equals(user.tenantCode())))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN,
                        "User has no enabled tenant membership"));
    }

    private boolean isEnabledMembership(AuthMembershipRepository.TenantMembership membership) {
        return membership.tenantEnabled() && membership.membershipEnabled();
    }

    private void requireEnabledMembership(AuthMembershipRepository.TenantMembership membership) {
        if (!membership.tenantEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant is disabled");
        }
        if (!membership.membershipEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant membership is disabled");
        }
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
