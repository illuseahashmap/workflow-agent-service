package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit account suspension is not a transient requests-per-minute limit. */
final class ProviderUsageLimit {
    private ProviderUsageLimit() {}

    static boolean exceeded(ObjectMapper json, String body) {
        if (body == null || body.length() > 1024 * 1024) {
            return false;
        }
        try {
            JsonNode error =
                    json.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(body);
            return error != null
                    && "SetLimitExceeded".equals(error.path("error").path("code").asText());
        } catch (java.io.IOException malformed) {
            return false;
        }
    }
}
