package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** HTTP validation stays outside the knowledge application port. */
public final class KnowledgeRequests {
    private KnowledgeRequests() {}

    public record Profile(@NotBlank @Size(max = 128) String profileCode,
                          @NotBlank @Pattern(regexp = "KEYWORD|VECTOR|HYBRID") String strategy,
                          @Min(1) @Max(50) int maxResults,
                          @DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") double minimumScore,
                          @Positive long indexVersionId,
                          @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 128) String> knowledgeScopes) {
        public KnowledgeManagementPort.CreateProfile command() {
            return new KnowledgeManagementPort.CreateProfile(profileCode, strategy, maxResults, minimumScore,
                    indexVersionId, knowledgeScopes);
        }
    }

    public record Document(@NotBlank @Size(max = 128) String sourceCode, @Size(max = 255) String sourceName,
                           @NotBlank @Size(max = 512) String externalDocumentId, @Positive int version,
                           @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String contentHash,
                           @NotBlank @Size(max = 2_000_000) String content) {
        public KnowledgeManagementPort.CreateDocument command() {
            return new KnowledgeManagementPort.CreateDocument(sourceCode, sourceName, externalDocumentId,
                    version, contentHash, content);
        }
    }

    public record Grant(@NotBlank @Size(max = 128) String principalId,
                        @NotBlank @Size(max = 128) String scopeCode) {
        public KnowledgeManagementPort.GrantScope command() {
            return new KnowledgeManagementPort.GrantScope(principalId, scopeCode);
        }
    }
}
