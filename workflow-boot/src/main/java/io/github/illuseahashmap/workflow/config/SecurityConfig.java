package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.auth.interfaces.security.AuthSecurityFilter;
import io.github.illuseahashmap.workflow.security.infrastructure.token.ServiceTokenAuthenticationFilter;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.util.StringUtils;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public CurrentPrincipalProvider currentPrincipalProvider() {
        return CurrentPrincipalContext::current;
    }

    @Bean
    public TenantProvider tenantProvider() {
        return TenantContext::current;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthSecurityFilter authSecurityFilter,
                                                   ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .ignoringRequestMatchers(request -> "POST".equalsIgnoreCase(request.getMethod())
                                && ("/auth/login".equals(request.getRequestURI())
                                || "/auth/register".equals(request.getRequestURI())))
                        .ignoringRequestMatchers(request -> StringUtils.hasText(
                                request.getHeader(ServiceTokenAuthenticationFilter.TOKEN_HEADER))))
                .sessionManagement(configurer -> configurer
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/health", "/actuator/info", "/auth/csrf", "/auth/register", "/auth/login", "/auth/logout").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(serviceTokenAuthenticationFilter, CsrfFilter.class)
                .addFilterBefore(authSecurityFilter, ServiceTokenAuthenticationFilter.class)
                .build();
    }
}
