package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

class P17ProviderFailureEvidenceTest {
    @TempDir Path directory;

    @Test
    void diagnosticsRedactCredentialsAndBoundProviderText() throws Exception {
        var request =
                new ModelProviderRequest(
                        "https://example.invalid",
                        "synthetic-secret",
                        "model",
                        "private prompt",
                        "private input",
                        Duration.ofSeconds(1),
                        "trace");
        var failure =
                new ModelProviderException(
                        "MODEL_REJECTED",
                        ModelProviderFailureKind.PERMANENT,
                        "safe",
                        new IllegalStateException(
                                "synthetic-secret Bearer other-secret " + "x".repeat(2000)));
        var path = directory.resolve("failure.json");
        P17ProviderFailureEvidence.write(path, request, failure);
        String report = Files.readString(path);
        assertThat(report)
                .doesNotContain(
                        "synthetic-secret", "other-secret", "private prompt", "private input");
        var json = P17Pilot.JSON.readTree(report);
        assertThat(json.path("diagnostic").asText()).hasSize(1024).contains("[REDACTED]");
        assertThat(json.path("errorCode").asText()).isEqualTo("MODEL_REJECTED");
    }
}
