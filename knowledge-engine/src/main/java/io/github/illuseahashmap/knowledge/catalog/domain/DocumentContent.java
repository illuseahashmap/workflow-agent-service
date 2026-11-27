package io.github.illuseahashmap.knowledge.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical text and its server-computed identity. Whitespace within the document is preserved. */
public record DocumentContent(String text, String sha256) {
    public static DocumentContent from(String input) {
        String text = Objects.requireNonNull(input, "content must not be null");
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        try {
            return new DocumentContent(text, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
