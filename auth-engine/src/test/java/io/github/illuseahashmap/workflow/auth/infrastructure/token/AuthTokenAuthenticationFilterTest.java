package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthTokenAuthenticationFilterTest {

    private final AuthTokenAuthenticationFilter filter = new AuthTokenAuthenticationFilter(
            mock(AuthTokenService.class),
            new ObjectMapper(),
            mock(AuthTenantRepository.class),
            mock(AuthMembershipRepository.class),
            mock(AuthUserRepository.class),
            mock(AuthAuthorizationRepository.class),
            new AuthTokenProperties()
    );

    @Test
    void ignoresStaleCookieOnPublicBrowserAuthenticationEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/csrf");
        request.setCookies(new Cookie("workflow-agent.access-token", "obsolete-token"));

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void stillAuthenticatesCookieOnProtectedEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.setCookies(new Cookie("workflow-agent.access-token", "obsolete-token"));

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void doesNotIgnoreExplicitBearerCredentialsOnPublicEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/csrf");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
