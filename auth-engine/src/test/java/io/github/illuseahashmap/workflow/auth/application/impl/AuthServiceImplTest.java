package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.port.AuthenticationAttemptGuard;
import io.github.illuseahashmap.workflow.auth.application.port.AuthTokenIssuer;
import io.github.illuseahashmap.workflow.auth.application.port.PasswordHasher;
import io.github.illuseahashmap.workflow.auth.application.port.SelfRegistrationPolicy;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
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
}
