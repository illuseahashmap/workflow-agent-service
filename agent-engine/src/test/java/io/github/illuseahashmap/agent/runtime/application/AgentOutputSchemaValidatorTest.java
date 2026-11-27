package io.github.illuseahashmap.agent.runtime.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentOutputSchemaValidatorTest {
    private final AgentOutputSchemaValidator validator =
            new AgentOutputSchemaValidator(new ObjectMapper());
    private static final String SCHEMA =
            """
            {"type":"object","additionalProperties":false,"required":["action","decision"],
            "properties":{"action":{"type":"string","enum":["ANSWER","HUMAN_REVIEW"]},
            "decision":{"type":"string","enum":["ORDER_READY","ORDER_NOT_READY"]}}}
            """;

    @Test
    void acceptsPublishedBusinessVocabulary() {
        validator.validateDefinition(SCHEMA);
        validator.validateOutput(
                SCHEMA,
                """
                {"action":"ANSWER","decision":"ORDER_READY"}
                """);
        validator.validateOutput("{}", "{\"legacy\":true}");
        validator.validateOutput(null, "legacy plain text");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"action\":\"CONDITIONS_MET\",\"decision\":\"ORDER_READY\"}",
                "{\"action\":\"ANSWER\",\"decision\":\"All conditions met\"}",
                "{\"action\":\"ANSWER\",\"decision\":\"ORDER_READY\",\"extra\":true}",
                "{\"action\":\"ANSWER\",\"decision\":\"ORDER_READY\"} {}",
                "",
                "null"
            })
    void rejectsUnpublishedOrMalformedFinalAnswers(String content) {
        assertThatThrownBy(() -> validator.validateOutput(SCHEMA, content))
                .isInstanceOf(AgentExecutionException.class);
        assertThatThrownBy(() -> validator.validateInput(SCHEMA, content))
                .isInstanceOf(AgentExecutionException.class);
    }

    @Test
    void appliesNestedClosedObjectsArrayEnumsAndConstants() {
        String schema =
                """
                {"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["state"],"properties":{"state":{"enum":[{"code":1}]},"tag":{"const":"v1"}}}}
                """;
        validator.validateDefinition(schema);
        validator.validateOutput(schema, "[{\"state\":{\"code\":1.0},\"tag\":\"v1\"}]");
        assertThatThrownBy(() -> validator.validateOutput(schema, "[{\"state\":{\"code\":2}}]"))
                .isInstanceOf(AgentExecutionException.class);
        assertThatThrownBy(
                        () ->
                                validator.validateOutput(
                                        schema, "[{\"state\":{\"code\":1},\"tag\":\"v2\"}]"))
                .isInstanceOf(AgentExecutionException.class);
        validator.validateOutput(
                "{\"additionalProperties\":{\"type\":\"integer\"}}", "{\"count\":1}");
        assertThatThrownBy(
                        () ->
                                validator.validateOutput(
                                        "{\"additionalProperties\":false}", "{\"count\":1}"))
                .isInstanceOf(AgentExecutionException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"{\"enum\":[]}", "{\"enum\":\"ANSWER\"}", "{\"additionalProperties\":2}"})
    void rejectsMalformedConstraintsAtPublication(String schema) {
        assertThatThrownBy(() -> validator.validateDefinition(schema))
                .isInstanceOf(AgentExecutionException.class);
    }
}
