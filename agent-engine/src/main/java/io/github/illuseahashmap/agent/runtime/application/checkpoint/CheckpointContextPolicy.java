package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic Checkpoint context assembly. It preserves the immutable prefix and
 * latest execution evidence while replacing omitted history with a content digest.
 */
@Component
public class CheckpointContextPolicy {

    private static final String OMITTED_MARKER = "\n\n[checkpoint history omitted sha256=%s]\n\n";

    private final int maximumCharacters;
    private final int maximumInlineToolResultCharacters;

    @Autowired
    public CheckpointContextPolicy(
            @Value("${workflow.agent.platform.checkpoint.maximum-context-characters:40000}") int maximumCharacters,
            @Value("${workflow.agent.platform.checkpoint.maximum-inline-tool-result-characters:2048}")
            int maximumInlineToolResultCharacters
    ) {
        this.maximumCharacters = Math.max(4_096, Math.min(maximumCharacters, 200_000));
        this.maximumInlineToolResultCharacters = Math.max(256,
                Math.min(maximumInlineToolResultCharacters, 8_192));
    }

    public CheckpointContextPolicy(int maximumCharacters) {
        this(maximumCharacters, 2_048);
    }

    public String assembleInitial(String routeJson, String input) {
        return compact("结构化执行计划：\n" + safe(routeJson) + "\n\n用户请求：\n" + safe(input));
    }

    public String appendToolResult(String context, String toolName, String output, String resultReference) {
        String reference = resultReference == null || resultReference.isBlank()
                ? "unavailable" : resultReference;
        String safeOutput = safe(output);
        String preview = prefixByCodePoints(safeOutput, maximumInlineToolResultCharacters);
        String omitted = preview.length() == safeOutput.length() ? ""
                : "\n[tool result externalized chars=" + safeOutput.length()
                        + " sha256=" + digest(safeOutput) + "]";
        return compact(safe(context) + "\n\n工具 " + safe(toolName)
                + " 返回（权威引用 " + reference + "）：\n" + preview + omitted);
    }

    public String compact(String value) {
        String safeValue = safe(value);
        if (safeValue.length() <= maximumCharacters) {
            return safeValue;
        }
        String omittedDigest = digest(safeValue);
        String marker = OMITTED_MARKER.formatted(omittedDigest);
        int available = maximumCharacters - marker.length();
        int prefixLength = available / 2;
        int suffixLength = available - prefixLength;
        int prefixEnd = safePrefixBoundary(safeValue, prefixLength);
        int suffixStart = safeSuffixBoundary(safeValue, safeValue.length() - suffixLength);
        return safeValue.substring(0, prefixEnd) + marker + safeValue.substring(suffixStart);
    }

    public String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public boolean matchesDigest(String value, String expectedDigest) {
        return expectedDigest == null || expectedDigest.isBlank() || digest(value).equals(expectedDigest);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String prefixByCodePoints(String value, int maximumChars) {
        if (value.length() <= maximumChars) {
            return value;
        }
        return value.substring(0, safePrefixBoundary(value, maximumChars));
    }

    private int safePrefixBoundary(String value, int requested) {
        int boundary = Math.max(0, Math.min(requested, value.length()));
        if (boundary > 0 && boundary < value.length()
                && Character.isHighSurrogate(value.charAt(boundary - 1))) {
            boundary--;
        }
        return boundary;
    }

    private int safeSuffixBoundary(String value, int requested) {
        int boundary = Math.max(0, Math.min(requested, value.length()));
        if (boundary > 0 && boundary < value.length()
                && Character.isLowSurrogate(value.charAt(boundary))) {
            boundary++;
        }
        return boundary;
    }
}
