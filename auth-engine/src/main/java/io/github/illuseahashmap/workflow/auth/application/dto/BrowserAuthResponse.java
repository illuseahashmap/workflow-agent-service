package io.github.illuseahashmap.workflow.auth.application.dto;

import java.time.OffsetDateTime;
import java.util.Set;

/** Browser-safe authentication response; the access token is only transported by HttpOnly cookie. */
public record BrowserAuthResponse(
        String tokenType,
        long expiresIn,
        OffsetDateTime expiresAt,
        String userId,
        String username,
        String displayName,
        String tenantCode,
        Set<String> roles,
        Set<String> permissions
) {

    public static BrowserAuthResponse from(AuthTokenResponse response) {
        return new BrowserAuthResponse(
                response.tokenType(), response.expiresIn(), response.expiresAt(),
                response.userId(), response.username(), response.displayName(),
                response.tenantCode(), response.roles(), response.permissions());
    }
}
