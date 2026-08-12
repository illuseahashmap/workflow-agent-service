package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentInputMappingResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentInputMappingResolver resolver = new AgentInputMappingResolver(objectMapper);

    @Test
    void emptyMappingProducesEmptyInputInsteadOfLeakingProcessVariables() throws Exception {
        String input = resolver.resolve("{}", Map.of("customer", "visible", "authenticatedUser", "hidden"));

        assertThat(objectMapper.readTree(input).path("input").isEmpty()).isTrue();
    }

    @Test
    void explicitNestedMappingOnlyIncludesDeclaredFields() throws Exception {
        String input = resolver.resolve(
                "{\"customer.name\":\"order.customerName\"}",
                Map.of("order", Map.of("customerName", "Alice", "secret", "never-copy")));

        assertThat(objectMapper.readTree(input).path("input").toString())
                .isEqualTo("{\"customer\":{\"name\":\"Alice\"}}");
    }
}
