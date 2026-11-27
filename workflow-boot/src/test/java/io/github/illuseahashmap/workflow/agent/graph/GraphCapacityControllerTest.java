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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.exception.GlobalExceptionHandler;

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

class GraphCapacityControllerTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import({GraphCapacityController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean
        JdbcGraphCapacityConfiguration capacity() {
            return mock(JdbcGraphCapacityConfiguration.class);
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(r -> r.anyRequest().authenticated()).build();
        }
    }

    @Test
    void platformRoleCsrfAndTrustedContextAreEnforced() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var store = context.getBean(JdbcGraphCapacityConfiguration.class);
            String body =
                    "{\"revision\":0,\"overrides\":{\"outstanding\":null,\"active\":2,\"calls\":3}}";
            mvc.perform(get("/agent-runtime/capacity").with(user("tenant").roles("TENANT_ADMIN")))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            put("/agent-runtime/capacity/tenants/alpha")
                                    .with(user("tenant").roles("TENANT_ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            put("/agent-runtime/capacity/tenants/alpha")
                                    .with(user("admin").roles("PLATFORM_ADMIN"))
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(store);
            doAnswer(
                            call -> {
                                assertThat(TrustedDataAccessContext.isSystemWorker()).isTrue();
                                throw new BusinessException(ErrorCode.CONFLICT, "changed");
                            })
                    .when(store)
                    .updateTenant(eq("alpha"), any(), eq("admin"));
            mvc.perform(
                            put("/agent-runtime/capacity/tenants/alpha")
                                    .with(user("admin").roles("PLATFORM_ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isConflict());
            assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        }
    }
}
