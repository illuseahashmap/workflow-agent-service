package io.github.illuseahashmap.agent.runtime.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import org.junit.jupiter.api.Test;

class AgentInputSchemaValidatorTest {

    private final AgentOutputSchemaValidator validator = new AgentOutputSchemaValidator(new ObjectMapper());

    @Test
    void acceptsNestedObjectsAndArrays() {
        String schema = """
                {"type":"object","required":["customer","tags"],"properties":{
                  "customer":{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}},
                  "tags":{"type":"array","items":{"type":"string"}}
                }}
                """;

        validator.validateInput(schema, ""
                + "{\"customer\":{\"name\":\"Alice\"},\"tags\":[\"vip\"]}");
    }

    @Test
    void rejectsMissingRequiredField() {
        String schema = ""
                + "{\"type\":\"object\",\"required\":[\"customer\"],"
                + "\"properties\":{\"customer\":{\"type\":\"string\"}}}";

        assertThatThrownBy(() -> validator.validateInput(schema, "{}"))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("input contract");
    }
}
