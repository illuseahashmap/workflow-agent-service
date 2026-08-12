package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentVersionCatalog;
import io.github.illuseahashmap.workflow.process.domain.AgentProcessFailurePolicy;
import io.github.illuseahashmap.workflow.process.domain.AgentTaskBinding;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentBindingDeploymentValidatorTest {

    private final AgentVersionCatalog catalog = mock(AgentVersionCatalog.class);
    private final AgentBindingDeploymentValidator validator =
            new AgentBindingDeploymentValidator(catalog, new ObjectMapper());

    @Test
    void rejectsMissingRequiredInputMapping() {
        when(catalog.findPublished("tenant-a", 42L)).thenReturn(Optional.of(
                new AgentVersionCatalog.PublishedAgentVersion(
                        42L, "MODEL_ONLY", 300,
                        "{\"type\":\"object\",\"required\":[\"customer\"]}", "{}")));
        var binding = new AgentTaskBinding(
                "agentReview", "Review", 42L, "{}", "{}",
                AgentProcessFailurePolicy.HOLD_FOR_OPERATIONS, 120);

        assertThatThrownBy(() -> validator.validate("tenant-a", List.of(binding)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer");
    }

    @Test
    void rejectsUnsafeOutputVariable() {
        when(catalog.findPublished("tenant-a", 42L)).thenReturn(Optional.of(
                new AgentVersionCatalog.PublishedAgentVersion(
                        42L, "MODEL_ONLY", 300, null, "{}")));
        var binding = new AgentTaskBinding(
                "agentReview", "Review", 42L, "{}", "{\"decision\":\"tenantId\"}",
                AgentProcessFailurePolicy.HOLD_FOR_OPERATIONS, 120);

        assertThatThrownBy(() -> validator.validate("tenant-a", List.of(binding)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsafe");
    }

    @Test
    void rejectsUnknownInputAndOutputSchemaPaths() {
        when(catalog.findPublished("tenant-a", 42L)).thenReturn(Optional.of(
                new AgentVersionCatalog.PublishedAgentVersion(
                        42L, "MODEL_ONLY", 300,
                        "{\"type\":\"object\",\"properties\":{\"customer\":{\"type\":\"string\"}}}",
                        "{\"type\":\"object\",\"properties\":{\"decision\":{\"type\":\"string\"}}}")));
        var unknownInput = new AgentTaskBinding(
                "agentReview", "Review", 42L, "{\"unknown\":\"source\"}", "{}",
                AgentProcessFailurePolicy.HOLD_FOR_OPERATIONS, 120);
        var unknownOutput = new AgentTaskBinding(
                "agentReview", "Review", 42L, "{}", "{\"unknown\":\"agentDecision\"}",
                AgentProcessFailurePolicy.HOLD_FOR_OPERATIONS, 120);

        assertThatThrownBy(() -> validator.validate("tenant-a", List.of(unknownInput)))
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> validator.validate("tenant-a", List.of(unknownOutput)))
                .hasMessageContaining("unknown path");
    }

    @Test
    void rejectsManualReviewUntilHumanTaskCapabilityExists() {
        when(catalog.findPublished("tenant-a", 42L)).thenReturn(Optional.of(
                new AgentVersionCatalog.PublishedAgentVersion(
                        42L, "MODEL_ONLY", 300, null, null)));
        var binding = new AgentTaskBinding(
                "agentReview", "Review", 42L, "{}", "{}",
                AgentProcessFailurePolicy.MANUAL_REVIEW, 120);

        assertThatThrownBy(() -> validator.validate("tenant-a", List.of(binding)))
                .hasMessageContaining("unavailable");
    }
}
