package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.INVALID_REQUEST;

/** Deliberately independent of permissive application-wide Jackson settings. */
public final class GraphGatewayCodec {
    public static final int MAX_WIRE_BYTES = 262144;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                    .maxStringLength(65536).maxNumberLength(40).build()).build())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public <T> T read(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length > MAX_WIRE_BYTES) { throw new GraphGatewayException(INVALID_REQUEST); }
        try {
            T value = json.readValue(bytes, type);
            if (value == null) { throw new GraphGatewayException(INVALID_REQUEST); }
            return value;
        }
        catch (Exception failure) { throw new GraphGatewayException(INVALID_REQUEST); }
    }
    public byte[] write(Object value) {
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > MAX_WIRE_BYTES) { throw new GraphGatewayException(INVALID_REQUEST); }
            return bytes;
        } catch (Exception failure) { throw new GraphGatewayException(INVALID_REQUEST); }
    }
    public String fingerprint(Object value) { return sha256(write(sorted(json.valueToTree(value)))); }
    public Map<String, Object> arguments(String value) {
        JsonNode root = read(value.getBytes(StandardCharsets.UTF_8), JsonNode.class);
        if (!root.isObject()) { throw new GraphGatewayException(INVALID_REQUEST); }
        return json.convertValue(root, new TypeReference<Map<String, Object>>() { });
    }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var entries = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> entries.put(entry.getKey(), sorted(entry.getValue())));
            ObjectNode result = json.createObjectNode();
            entries.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }
}
