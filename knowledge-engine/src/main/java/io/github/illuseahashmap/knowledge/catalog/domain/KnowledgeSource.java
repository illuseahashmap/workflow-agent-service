package io.github.illuseahashmap.knowledge.catalog.domain;

import java.util.Objects;

/** Tenant-owned logical source; content is versioned separately. */
public record KnowledgeSource(String tenantCode, String sourceCode, String name, boolean enabled) {
    public KnowledgeSource {
        tenantCode = text(tenantCode, "tenantCode");
        sourceCode = text(sourceCode, "sourceCode");
        name = text(name, "name");
    }
    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
