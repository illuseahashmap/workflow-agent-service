package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import java.util.List;

class KnowledgeRequestsTest {
    @Test
    void jsonPolicyOmissionPreservesLegacyDefaultAndUnknownPolicyIsRejected() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var oldRequest =
                """
                {"profileCode":"p","strategy":"KEYWORD","maxResults":10,"minimumScore":0,
                 "indexVersionId":1,"knowledgeScopes":["policy"]}
                """;
        assertThat(
                        json.readValue(oldRequest, KnowledgeRequests.Profile.class)
                                .command()
                                .queryExpansionPolicy())
                .isEqualTo(
                        io.github.illuseahashmap.knowledge.retrieval.domain.QueryExpansionPolicy
                                .DISABLED);
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(oldRequest);
        node.put("queryExpansionPolicy", "RRF_V1");
        assertThat(
                        json.treeToValue(node, KnowledgeRequests.Profile.class)
                                .command()
                                .queryExpansionPolicy())
                .isEqualTo(
                        io.github.illuseahashmap.knowledge.retrieval.domain.QueryExpansionPolicy
                                .RRF_V1);
        node.put("queryExpansionPolicy", "RRF_V99");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> json.treeToValue(node, KnowledgeRequests.Profile.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void validatesBoundsAndDoesNotAcceptUnsupportedStrategyOrBlankScope() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(
                            validator.validate(
                                    new KnowledgeRequests.Profile(
                                            "p", "GRAPH", 0, -1, 0, List.of(""))))
                    .hasSizeGreaterThanOrEqualTo(5);
            assertThat(
                            validator.validate(
                                    new KnowledgeRequests.Profile(
                                            "p", "HYBRID", 10, 0.2, 1, List.of("policies"))))
                    .isEmpty();
            assertThat(
                            validator.validate(
                                    new KnowledgeRequests.Document("", null, "", 0, "fake", "")))
                    .hasSizeGreaterThanOrEqualTo(5);
        }
    }
}
