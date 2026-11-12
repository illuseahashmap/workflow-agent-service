package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import io.github.illuseahashmap.workflow.auth.interfaces.security.AuthSecurityFilter;
import io.github.illuseahashmap.workflow.config.SecurityConfig;
import io.github.illuseahashmap.workflow.security.infrastructure.token.ServiceTokenAuthenticationFilter;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphHostControllerTest {
    private static final String TOKEN = "worker-secret-for-isolated-host-tests";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcGraphExecutionStore store;

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({GraphHostSecurity.class, SecurityConfig.class})
    static class Config {
        @Bean JdbcGraphExecutionStore executions() { return mock(JdbcGraphExecutionStore.class); }
        @Bean GraphGatewayProtocol gateway() { return mock(GraphGatewayProtocol.class); }
        @Bean JdbcGraphResourceConfiguration resources() { return mock(JdbcGraphResourceConfiguration.class); }
        @Bean GraphResourceController resourceController(JdbcGraphResourceConfiguration resources) { return new GraphResourceController(resources); }
        @Bean GraphHostController host(JdbcGraphExecutionStore store, GraphGatewayProtocol gateway) {
            return new GraphHostController(new GraphHostConfiguration.GraphHostSettings(TOKEN, "a".repeat(64), 90, false), store, gateway);
        }
        @Bean AuthSecurityFilter auth() throws Exception {
            var filter = mock(AuthSecurityFilter.class);
            doAnswer(call -> {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1));
                return null;
            })
                    .when(filter).doFilter(any(), any(), any());
            return filter;
        }
        @Bean ServiceTokenAuthenticationFilter service() throws Exception {
            var filter = mock(ServiceTokenAuthenticationFilter.class);
            doAnswer(call -> {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1));
                return null;
            })
                    .when(filter).doFilter(any(), any(), any());
            return filter;
        }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        store = context.getBean(JdbcGraphExecutionStore.class);
        clearInvocations(context.getBean(AuthSecurityFilter.class), context.getBean(ServiceTokenAuthenticationFilter.class));
    }
    @AfterEach void close() { context.close(); }

    @Test void cookieOrServiceHeadersNeverGrantWorkerAccess() throws Exception {
        mvc.perform(post("/internal/agent-dispatch/v1/claim").secure(true).with(user("admin").roles("PLATFORM_ADMIN"))
                .header("X-Workflow-Token", "anything").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void validCredentialClaimsOnlyConfiguredArtifactAndRestoresTrustedContext() throws Exception {
        when(store.claimNext("worker-one", 90, "a".repeat(64))).thenReturn(Optional.empty());
        mvc.perform(post("/internal/agent-dispatch/v1/claim").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content("{\"artifact_digest\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"claim\":null}"));
        verify(store).claimNext("worker-one", 90, "a".repeat(64));
        assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        verifyNoInteractions(context.getBean(AuthSecurityFilter.class), context.getBean(ServiceTokenAuthenticationFilter.class));
    }
    @Test void insecureOversizeMalformedAndForeignArtifactRequestsFailBeforeClaim() throws Exception {
        mvc.perform(post("/internal/agent-dispatch/v1/claim").header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/agent-dispatch/v1/claim").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content(new byte[GraphGatewayCodec.MAX_WIRE_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/internal/agent-dispatch/v1/claim").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content("{\"artifact_digest\":\"" + "b".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/agent-dispatch/v1/renew").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content("{\"claim\":null}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void accountConfigurationRequiresPlatformRoleAndCsrf() throws Exception {
        String payload = "{\"resources\":[],\"bindings\":[]}";
        mvc.perform(put("/agent-runtime/resources").with(user("tenant").roles("TENANT_ADMIN")).with(csrf())
                .contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(put("/agent-runtime/resources").with(user("admin").roles("PLATFORM_ADMIN"))
                .contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(put("/agent-runtime/resources").with(user("admin").roles("PLATFORM_ADMIN")).with(csrf())
                .contentType("application/json").content(payload)).andExpect(status().isOk());
        verify(context.getBean(JdbcGraphResourceConfiguration.class)).configure(any(), eq("admin"));
        assertThat(context.getBean(SecurityConfig.class).authFilterRegistration(context.getBean(AuthSecurityFilter.class)).isEnabled()).isFalse();
        assertThat(context.getBean(SecurityConfig.class).serviceFilterRegistration(context.getBean(ServiceTokenAuthenticationFilter.class)).isEnabled()).isFalse();
    }

    @Test void batchRenewalAndHandoffCarryFencedLeaseAndRejectForeignWorker() throws Exception {
        var binding = new io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding(
                "alpha", 1, 10, "a".repeat(64), 1, "input:one", "subject:one", System.currentTimeMillis() + 60000, 10, 20);
        var claim = new io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Claim(
                "command:1", binding, 2, "graph.secret", "worker-one", java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 60000), null, null);
        var codec = new GraphGatewayCodec();
        when(store.renew(any(), eq(90))).thenReturn(true);
        mvc.perform(post("/internal/agent-dispatch/v1/renew-batch").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one")
                .content(codec.write(new GraphHostController.RenewRequest(java.util.List.of(GraphHostController.Lease.of(claim))))))
                .andExpect(status().isOk()).andExpect(content().json("{\"accepted\":[true]}"));
        verify(store).renew(claim, 90);
        clearInvocations(store);
        mvc.perform(post("/internal/agent-dispatch/v1/renew").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-two").content(codec.write(new GraphHostController.LeaseRequest(GraphHostController.Lease.of(claim)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test void unregisteredInternalRoutesAreDeniedEvenWithWorkerSecret() throws Exception {
        mvc.perform(post("/internal/agent-dispatch/v1/reserve").secure(true).header("Authorization", "Bearer " + TOKEN)
                .header("X-Graph-Worker", "worker-one").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test void accountAuditUsesPrincipalIdentityNotRecordToString() throws Exception {
        var principal = new io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal(
                "USER", "user-7", "admin", "Administrator", "alpha", java.util.Set.of("PLATFORM_ADMIN"), java.util.Set.of());
        var authenticated = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
        mvc.perform(put("/agent-runtime/resources")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(authenticated)).with(csrf())
                .contentType("application/json").content("{\"resources\":[],\"bindings\":[]}"))
                .andExpect(status().isOk());
        verify(context.getBean(JdbcGraphResourceConfiguration.class)).configure(any(), eq("user-7"));
    }
}
