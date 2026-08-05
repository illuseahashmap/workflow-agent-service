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
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

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
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/health", "/actuator/info", "/auth/register", "/auth/login", "/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(serviceTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authSecurityFilter, ServiceTokenAuthenticationFilter.class)
                .build();
    }
}
