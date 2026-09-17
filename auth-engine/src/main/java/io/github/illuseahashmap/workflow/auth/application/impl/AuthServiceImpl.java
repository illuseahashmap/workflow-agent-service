package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.ChangePasswordRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateProfileRequest;
import io.github.illuseahashmap.workflow.auth.application.port.AuthTokenIssuer;
import io.github.illuseahashmap.workflow.auth.application.port.AuthenticationAttemptGuard;
import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import io.github.illuseahashmap.workflow.auth.application.port.SelfRegistrationPolicy;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE_CODE = "USER";
    private static final String LOGIN_OPERATION = "LOGIN";
    private static final String REGISTER_OPERATION = "REGISTER";

    private final AuthUserRepository userRepository;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthAuthorizationRepository authorizationRepository;
    private final PasswordHasher passwordHasher;
    private final AuthTokenIssuer tokenIssuer;
    private final CurrentPrincipalProvider principalProvider;
    private final SelfRegistrationPolicy selfRegistrationPolicy;
    private final AuthenticationAttemptGuard authenticationAttemptGuard;
    private final String dummyPasswordHash;

    public AuthServiceImpl(AuthUserRepository userRepository,
                           AuthTenantRepository tenantRepository,
                           AuthMembershipRepository membershipRepository,
                           AuthAuthorizationRepository authorizationRepository,
                           PasswordHasher passwordHasher,
                           AuthTokenIssuer tokenIssuer,
                           CurrentPrincipalProvider principalProvider,
                           SelfRegistrationPolicy selfRegistrationPolicy,
                           AuthenticationAttemptGuard authenticationAttemptGuard) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationRepository = authorizationRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.principalProvider = principalProvider;
        this.selfRegistrationPolicy = selfRegistrationPolicy;
        this.authenticationAttemptGuard = authenticationAttemptGuard;
        this.dummyPasswordHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public AuthTokenResponse register(RegisterRequest request, String sourceAddress) {
        if (!selfRegistrationPolicy.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Self-registration is disabled");
        }
        String username = normalizeUsername(request.username());
        authenticationAttemptGuard.assertAllowed(REGISTER_OPERATION, username, sourceAddress);
        try {
            String tenantCode = selfRegistrationPolicy.tenantCode().trim();
            String displayName = StringUtils.hasText(request.displayName())
                    ? request.displayName().trim() : username;
            AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(tenantCode)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Registration unavailable"));
            if (!tenant.enabled() || userRepository.existsByUsername(username)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Registration unavailable");
            }
            AuthUser user = userRepository.save(
                    UUID.randomUUID().toString(), username, displayName,
                    passwordHasher.hash(request.password()), tenantCode);
            membershipRepository.add(user.userId(), tenantCode);
            authorizationRepository.grantRole(user.userId(), tenantCode, DEFAULT_ROLE_CODE);
            Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), tenantCode);
            AuthTokenResponse response = tokenIssuer.issue(
                    user, tenantCode, Set.of(DEFAULT_ROLE_CODE), permissions);
            authenticationAttemptGuard.recordSuccess(REGISTER_OPERATION, username, sourceAddress);
            return response;
        } catch (BusinessException | DuplicateKeyException exception) {
            authenticationAttemptGuard.recordFailure(REGISTER_OPERATION, username, sourceAddress);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Registration cannot be completed");
        }
    }

    @Override
    public AuthTokenResponse login(LoginRequest request, String sourceAddress) {
        String username = normalizeUsername(request.username());
        authenticationAttemptGuard.assertAllowed(LOGIN_OPERATION, username, sourceAddress);
        AuthUser user = userRepository.findByUsername(username).orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.passwordHash();
        boolean passwordMatches = passwordHasher.matches(request.password(), passwordHash);
        if (user == null || !user.enabled() || !passwordMatches) {
            authenticationAttemptGuard.recordFailure(LOGIN_OPERATION, username, sourceAddress);
            throw invalidCredentials();
        }
        try {
            AuthMembershipRepository.TenantMembership membership = selectLoginMembership(user);
            String tenantCode = membership.tenantCode();
            Set<String> roles = authorizationRepository.findRoleCodes(user.userId(), tenantCode);
            if (roles.isEmpty()) {
                roles = Set.of(DEFAULT_ROLE_CODE);
            }
            Set<String> permissions = authorizationRepository.findPermissionCodes(user.userId(), tenantCode);
            AuthTokenResponse response = tokenIssuer.issue(user, tenantCode, roles, permissions);
            authenticationAttemptGuard.recordSuccess(LOGIN_OPERATION, username, sourceAddress);
            return response;
        } catch (BusinessException exception) {
            authenticationAttemptGuard.recordFailure(LOGIN_OPERATION, username, sourceAddress);
            throw invalidCredentials();
        }
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
    @Transactional
    public CurrentUserResponse updateProfile(UpdateProfileRequest request) {
        CurrentPrincipal principal = principalProvider.current();
        String displayName = request.displayName().trim();
        AuthUser user = userRepository.updateDisplayName(principal.principalId(), displayName);
        return new CurrentUserResponse(
                user.userId(), user.username(), user.displayName(), principal.tenantCode(),
                principal.roles(), principal.permissions());
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        CurrentPrincipal principal = principalProvider.current();
        AuthUser user = userRepository.findByUserId(principal.principalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "User does not exist"));
        if (!passwordHasher.matches(request.currentPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordHasher.matches(request.newPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password must be different");
        }
        userRepository.updatePasswordHash(user.userId(), passwordHasher.hash(request.newPassword()));
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
            return selfRegistrationPolicy.tenantCode().trim();
        }
        return tenantCode.trim();
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
    }
}
