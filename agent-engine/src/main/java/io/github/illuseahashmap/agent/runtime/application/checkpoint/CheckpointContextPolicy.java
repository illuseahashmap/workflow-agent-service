package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Working-memory budgets are measured in UTF-16 units; never truncate a recovery envelope or authoritative input.
 */
@Component
public class CheckpointContextPolicy {

    // Storage format invariant, independent of any worker's prompt budget.
    static final int STORED_NOTES_LIMIT = 8_192;
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
                Math.min(maximumInlineToolResultCharacters, Math.min(STORED_NOTES_LIMIT, this.maximumCharacters / 4)));
    }

    public CheckpointContextPolicy(int maximumCharacters) {
        this(maximumCharacters, 2_048);
    }

    public int inlineLimit() { return maximumInlineToolResultCharacters; }

    public String preview(String value) {
        if (value == null || value.length() <= inlineLimit()) return value;
        String marker = "…[外置 sha256=" + digest(value) + "]";
        return prefixByCodePoints(value, inlineLimit() - marker.length()) + marker;
    }

    public void requireFits(long characters) {
        if (characters > maximumCharacters) {
            throw new io.github.illuseahashmap.agent.runtime.application.AgentExecutionException(
                    new io.github.illuseahashmap.agent.runtime.domain.AgentFailure("AGENT_CONTEXT_BUDGET_EXCEEDED",
                    io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.RESULT_POLICY, false,
                    io.github.illuseahashmap.agent.runtime.domain.ResultStatus.REJECTED, "Request exceeds the configured context budget"));
        }
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

}
