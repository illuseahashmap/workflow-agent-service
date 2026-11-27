package io.github.illuseahashmap.workflow.security.domain;

import java.time.OffsetDateTime;

public record ServiceClient(
        String clientCode,
        String secretKeyRef,
        String secretCiphertext,
        int secretVersion,
        String allowedTenantCodes,
        String allowedPaths,
        boolean enabled,
        OffsetDateTime expiresAt
) {

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
