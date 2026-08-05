package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.port.AuthTokenIssuer;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthTokenService implements AuthTokenIssuer {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_TYPE = "Bearer";

    private final AuthTokenProperties properties;
    private final ObjectMapper objectMapper;

    public AuthTokenService(AuthTokenProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AuthTokenResponse issue(AuthUser user, Set<String> roles, Set<String> permissions) {
        return issue(user, user.tenantCode(), roles, permissions);
    }

    @Override
    public AuthTokenResponse issue(AuthUser user, String tenantCode, Set<String> roles, Set<String> permissions) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getTtlSeconds());
        AuthTokenPayload payload = new AuthTokenPayload(
                properties.getIssuer(),
                user.userId(),
                user.username(),
                user.displayName(),
                tenantCode,
                now.getEpochSecond(),
                expiresAt.getEpochSecond(),
                roles,
                permissions
        );
        String token = encode(payload);
        return new AuthTokenResponse(
                TOKEN_TYPE,
                token,
                properties.getTtlSeconds(),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                user.userId(),
                user.username(),
                user.displayName(),
                tenantCode,
                roles,
                permissions
        );
    }

    public AuthTokenPayload verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing bearer token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token");
        }
        String payloadPart = parts[0];
        String signaturePart = parts[1];
        String expectedSignature = sign(payloadPart);
        if (!MessageDigestEquals.equals(signaturePart, expectedSignature)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token signature");
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadPart);
            AuthTokenPayload payload = objectMapper.readValue(payloadBytes, AuthTokenPayload.class);
            if (!properties.getIssuer().equals(payload.issuer())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token issuer");
            }
            if (payload.expiresAtEpochSeconds() < Instant.now().getEpochSecond()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Bearer token expired");
            }
            return payload;
        } catch (IllegalArgumentException | IOException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token");
        }
    }

    private String encode(AuthTokenPayload payload) {
        try {
            String payloadPart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            return payloadPart + "." + sign(payloadPart);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to issue bearer token");
        }
    }

    private String sign(String payloadPart) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to sign bearer token");
        }
    }

    public record AuthTokenPayload(
            String issuer,
            String userId,
            String username,
            String displayName,
            String tenantCode,
            long issuedAtEpochSeconds,
            long expiresAtEpochSeconds,
            Set<String> roles,
            Set<String> permissions
    ) {
    }

    private static final class MessageDigestEquals {

        private MessageDigestEquals() {
        }

        static boolean equals(String left, String right) {
            byte[] leftBytes = left == null ? new byte[0] : left.getBytes(StandardCharsets.UTF_8);
            byte[] rightBytes = right == null ? new byte[0] : right.getBytes(StandardCharsets.UTF_8);
            if (leftBytes.length != rightBytes.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < leftBytes.length; i++) {
                result |= leftBytes[i] ^ rightBytes[i];
            }
            return result == 0;
        }
    }
}
