package io.github.illuseahashmap.workflow.auth.application.dto;

import java.time.OffsetDateTime;
import java.util.Set;

public record AuthTokenResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        OffsetDateTime expiresAt,
        String userId,
        String username,
        String displayName,
        String tenantCode,
        Set<String> roles,
        Set<String> permissions
) {
}
