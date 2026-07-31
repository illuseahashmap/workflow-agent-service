package io.github.illuseahashmap.workflow.security.infrastructure.serviceauth;

public record ServiceTokenPayload(
        String clientCode,
        String tenantCode,
        Long timestamp,
        String nonce,
        String method,
        String path,
        String bodySha256,
        Integer tokenVersion
) {
}
