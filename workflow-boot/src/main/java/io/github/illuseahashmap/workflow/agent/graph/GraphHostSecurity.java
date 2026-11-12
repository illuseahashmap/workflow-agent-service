package io.github.illuseahashmap.workflow.agent.graph;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Internal protocols authenticate scoped tokens in their bounded host, never browser sessions or X-Workflow-Token. */
@Configuration(proxyBeanMethods=false)
public class GraphHostSecurity {
    @Bean @Order(0)
    public SecurityFilterChain graphInternalSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/agent-runtime/v1/**", "/internal/agent-dispatch/v1/**")
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Only the bounded, explicitly authenticated controller actions are reachable.
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST,
                        "/internal/agent-runtime/v1/authorize", "/internal/agent-runtime/v1/invoke",
                        "/internal/agent-dispatch/v1/claim", "/internal/agent-dispatch/v1/renew",
                        "/internal/agent-dispatch/v1/renew-batch", "/internal/agent-dispatch/v1/defer",
                        "/internal/agent-dispatch/v1/acknowledge").permitAll().anyRequest().denyAll()).build();
    }
}
