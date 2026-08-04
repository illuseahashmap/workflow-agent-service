package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.port.AuthenticationAttemptGuard;
import io.github.illuseahashmap.workflow.auth.application.port.AuthTokenIssuer;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthUserRepository userRepository;
    @Mock
    private AuthTenantRepository tenantRepository;
    @Mock
    private AuthMembershipRepository membershipRepository;
    @Mock
    private AuthAuthorizationRepository authorizationRepository;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private AuthTokenIssuer tokenIssuer;
    @Mock
    private CurrentPrincipalProvider principalProvider;
    @Mock
    private SelfRegistrationPolicy selfRegistrationPolicy;
    @Mock
    private AuthenticationAttemptGuard authenticationAttemptGuard;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        when(passwordHasher.hash(anyString())).thenReturn("dummy-password-hash");
        service = new AuthServiceImpl(
                userRepository, tenantRepository, membershipRepository, authorizationRepository,
                passwordHasher, tokenIssuer, principalProvider, selfRegistrationPolicy,
                authenticationAttemptGuard);
    }

    @Test
    void unknownUserReturnsGenericCredentialsErrorAndRecordsFailure() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
        when(passwordHasher.matches("wrong", "dummy-password-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("unknown", "wrong"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo("Invalid username or password");
                });

        verify(authenticationAttemptGuard).recordFailure("LOGIN", "unknown", "127.0.0.1");
    }

    @Test
    void disabledUserDoesNotLeakAccountState() {
        AuthUser disabled = new AuthUser(
                1L, "user-id", "disabled", "Disabled", "stored-hash",
                "tenant-a", false, null, null);
        when(userRepository.findByUsername("disabled")).thenReturn(Optional.of(disabled));
        when(passwordHasher.matches("correct", "stored-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("disabled", "correct"), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password");

        verify(authenticationAttemptGuard).recordFailure("LOGIN", "disabled", "127.0.0.1");
    }

    @Test
    void registersNormalUserWithDefaultRoleAndIssuesToken() {
        AuthUser user = user("new-user", "New User", "tenant-a");
        AuthTokenResponse token = token("new-user", "tenant-a");
        when(selfRegistrationPolicy.enabled()).thenReturn(true);
        when(selfRegistrationPolicy.tenantCode()).thenReturn(" tenant-a ");
        when(tenantRepository.findByTenantCode("tenant-a"))
                .thenReturn(Optional.of(new AuthTenantRepository.AuthTenant(
                        "tenant-id", "tenant-a", "Tenant A", true)));
        when(userRepository.existsByUsername("new-user")).thenReturn(false);
        when(userRepository.save(anyString(), eq("new-user"), eq("New User"),
                eq("dummy-password-hash"), eq("tenant-a"))).thenReturn(user);
        when(authorizationRepository.findPermissionCodes("user-id", "tenant-a"))
                .thenReturn(Set.of("workflow:read"));
        when(tokenIssuer.issue(user, "tenant-a", Set.of("USER"), Set.of("workflow:read")))
                .thenReturn(token);

        AuthTokenResponse result = service.register(
                new RegisterRequest(" New-User ", "password", " New User "), "127.0.0.1");

        assertThat(result).isSameAs(token);
        verify(membershipRepository).add("user-id", "tenant-a");
        verify(authorizationRepository).grantRole("user-id", "tenant-a", "USER");
        verify(authenticationAttemptGuard).recordSuccess("REGISTER", "new-user", "127.0.0.1");
    }

    @Test
    void registrationFailureUsesGenericErrorAndRecordsFailure() {
        when(selfRegistrationPolicy.enabled()).thenReturn(true);
        when(selfRegistrationPolicy.tenantCode()).thenReturn("tenant-a");
        when(tenantRepository.findByTenantCode("tenant-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("new-user", "password", null), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("Registration cannot be completed");
                });

        verify(authenticationAttemptGuard).recordFailure("REGISTER", "new-user", "127.0.0.1");
        verify(userRepository, never()).save(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void loginUsesCurrentTenantMembershipAndDefaultRoleWhenNoRolesAreStored() {
        AuthUser user = user("operator", "Operator", "tenant-a");
        AuthMembershipRepository.TenantMembership membership = membership(
                "user-id", "tenant-a", true, true);
        AuthTokenResponse token = token("operator", "tenant-a");
        when(userRepository.findByUsername("operator")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("password", "stored-hash")).thenReturn(true);
        when(membershipRepository.findByUserId("user-id")).thenReturn(List.of(membership));
        when(authorizationRepository.findRoleCodes("user-id", "tenant-a")).thenReturn(Set.of());
        when(authorizationRepository.findPermissionCodes("user-id", "tenant-a"))
                .thenReturn(Set.of("workflow:read"));
        when(tokenIssuer.issue(user, "tenant-a", Set.of("USER"), Set.of("workflow:read")))
                .thenReturn(token);

        assertThat(service.login(new LoginRequest(" Operator ", "password"), "127.0.0.1"))
                .isSameAs(token);
        verify(authenticationAttemptGuard).recordSuccess("LOGIN", "operator", "127.0.0.1");
    }

    @Test
    void loginRejectsUserWithoutEnabledMembership() {
        AuthUser user = user("operator", "Operator", "tenant-a");
        when(userRepository.findByUsername("operator")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("password", "stored-hash")).thenReturn(true);
        when(membershipRepository.findByUserId("user-id")).thenReturn(List.of(
                membership("user-id", "tenant-a", false, true)));

        assertThatThrownBy(() -> service.login(new LoginRequest("operator", "password"), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password");

        verify(authenticationAttemptGuard).recordFailure("LOGIN", "operator", "127.0.0.1");
    }

    @Test
    void switchTenantRejectsDisabledMembershipBeforeIssuingToken() {
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-id", "operator", "Operator", "tenant-a", Set.of("USER"), Set.of()));
        when(membershipRepository.find("user-id", "tenant-b"))
                .thenReturn(Optional.of(membership("user-id", "tenant-b", true, false)));

        assertThatThrownBy(() -> service.switchTenant(new SwitchTenantRequest(" tenant-b ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Tenant is disabled");

        verify(tokenIssuer, never()).issue(any(AuthUser.class), anyString(), anySet(), anySet());
    }

    @Test
    void tenantsMarksCurrentAndDisablesMembershipWhenTenantIsUnavailable() {
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-id", "operator", "Operator", "tenant-a", Set.of("USER"), Set.of()));
        when(membershipRepository.findByUserId("user-id")).thenReturn(List.of(
                membership("user-id", "tenant-a", true, true),
                membership("user-id", "tenant-b", false, true)));
        when(authorizationRepository.findRoleCodes("user-id", "tenant-a")).thenReturn(Set.of("USER"));
        when(authorizationRepository.findRoleCodes("user-id", "tenant-b"))
                .thenReturn(Set.of("TENANT_ADMIN"));

        assertThat(service.tenants())
                .extracting("tenantCode", "enabled", "current")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("tenant-a", true, true),
                        org.assertj.core.groups.Tuple.tuple("tenant-b", false, false));
    }

    private AuthUser user(String username, String displayName, String tenantCode) {
        return new AuthUser(1L, "user-id", username, displayName, "stored-hash", tenantCode,
                true, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AuthMembershipRepository.TenantMembership membership(
            String userId, String tenantCode, boolean membershipEnabled, boolean tenantEnabled) {
        return new AuthMembershipRepository.TenantMembership(
                userId, tenantCode + "-id", tenantCode, tenantCode.toUpperCase(),
                membershipEnabled, tenantEnabled, OffsetDateTime.now());
    }

    private AuthTokenResponse token(String username, String tenantCode) {
        return new AuthTokenResponse("Bearer", "token", 3600, OffsetDateTime.now(),
                "user-id", username, username, tenantCode, Set.of("USER"), Set.of());
    }
}
