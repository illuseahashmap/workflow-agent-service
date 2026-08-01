package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenAuthenticationFilter;
import io.github.illuseahashmap.workflow.security.infrastructure.token.ServiceTokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthTokenAuthenticationFilter authTokenAuthenticationFilter,
                                                   ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/health", "/actuator/info", "/auth/register", "/auth/login").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(serviceTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authTokenAuthenticationFilter, ServiceTokenAuthenticationFilter.class)
                .build();
    }
}
