package io.github.illuseahashmap.workflow.knowledge;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRequestsTest {
    @Test
    void validatesBoundsAndDoesNotAcceptUnsupportedStrategyOrBlankScope() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new KnowledgeRequests.Profile("p", "GRAPH", 0, -1, 0, List.of(""))))
                    .hasSizeGreaterThanOrEqualTo(5);
            assertThat(validator.validate(new KnowledgeRequests.Profile("p", "HYBRID", 10, 0.2, 1, List.of("policies"))))
                    .isEmpty();
            assertThat(validator.validate(new KnowledgeRequests.Document("", null, "", 0, "fake", "")))
                    .hasSizeGreaterThanOrEqualTo(5);
        }
    }
}
