package io.github.illuseahashmap.workflow.integration;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationHttpIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.token.secret",
                () -> "integration-test-auth-token-secret-at-least-32-bytes");
        registry.add("workflow.auth.token.cookie-secure", () -> "false");
        registry.add("workflow.auth.bootstrap-admin.enabled", () -> "false");
        registry.add("workflow.auth.self-registration.enabled", () -> "true");
        registry.add("workflow.auth.protection.failure-threshold", () -> "3");
        registry.add("workflow.auth.protection.base-lock-seconds", () -> "1");
    }

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void registrationLoginAndProtectedEndpointUseRealSecurityChain() throws Exception {
        MvcResult csrf = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        String csrfToken = com.jayway.jsonpath.JsonPath.read(
                csrf.getResponse().getContentAsString(), "$.data");

        mockMvc.perform(post("/auth/register")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"integration-user","displayName":"Integration User","password":"hello-integration"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"integration-user","password":"hello-integration"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn();
        Cookie authCookie = loginResult.getResponse().getCookie("workflow-agent.access-token");

        mockMvc.perform(get("/auth/me").cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("integration-user"));

        mockMvc.perform(get("/workflow/tenant").cookie(authCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void repeatedLoginFailuresAreSharedAndTemporarilyBlocked() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .with(protectedSource())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"protected-user","password":"protected-password"}
                                """))
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/auth/login")
                            .with(protectedSource())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"protected-user","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid username or password"));
        }

        mockMvc.perform(post("/auth/login")
                        .with(protectedSource())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"protected-user","password":"protected-password"}
                                """))
                .andExpect(status().isTooManyRequests());

        Thread.sleep(1100);

        mockMvc.perform(post("/auth/login")
                        .with(protectedSource())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"protected-user","password":"protected-password"}
                                """))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor protectedSource() {
        return request -> {
            request.setRemoteAddr("198.51.100.10");
            return request;
        };
    }
}
