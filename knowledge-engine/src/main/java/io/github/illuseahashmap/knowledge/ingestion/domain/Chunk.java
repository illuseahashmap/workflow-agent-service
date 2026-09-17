package io.github.illuseahashmap.knowledge.ingestion.domain;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Deterministic, traceable text unit; storage and vector types stay outside the domain. */
public record Chunk(
        String chunkId,
        DocumentVersion documentVersion,
        int ordinal,
        String text,
        String contentFingerprint
) {
    public Chunk {
        chunkId = textValue(chunkId, "chunkId");
        documentVersion = Objects.requireNonNull(documentVersion, "documentVersion must not be null");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        text = textValue(text, "text");
        contentFingerprint = textValue(contentFingerprint, "contentFingerprint");
    }

    public static Chunk of(DocumentVersion documentVersion, int ordinal, String text) {
        String fingerprint = sha256(text);
        String chunkId = documentVersion.contentHash() + ":" + ordinal + ":" + fingerprint.substring(0, 16);
        return new Chunk(chunkId, documentVersion, ordinal, text, fingerprint);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint document chunk", exception);
        }
    }

    private static String textValue(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
