package io.github.illuseahashmap.workflow.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallReconciliation;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.UUID;

class GraphCallReconciliationControllerTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import(GraphCallReconciliationController.class)
    static class Config {
        @Bean
        JdbcGraphCallReconciliation store() {
            return mock(JdbcGraphCallReconciliation.class);
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(r -> r.anyRequest().authenticated()).build();
        }
    }

    @Test
    void onlyPlatformOperatorsWithCsrfCanAttest() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var store = context.getBean(JdbcGraphCallReconciliation.class);
            String path = "/agent-runtime/call-reconciliations/" + UUID.randomUUID() + "/approve";
            String body = "{\"evidenceSha256\":\"" + "a".repeat(64) + "\"}";
            mvc.perform(
                            get("/agent-runtime/call-reconciliations")
                                    .with(user("tenant").roles("TENANT_ADMIN")))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post(path)
                                    .with(user("admin").roles("PLATFORM_ADMIN"))
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post(path)
                                    .with(user("tenant").roles("TENANT_ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(store);
            doAnswer(
                            call -> {
                                assertThat(TrustedDataAccessContext.isSystemWorker()).isTrue();
                                return true;
                            })
                    .when(store)
                    .approve(any(), eq("a".repeat(64)), eq("reviewer"));
            mvc.perform(
                            post(path)
                                    .with(user("reviewer").roles("PLATFORM_ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk());
            assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        }
    }
}
